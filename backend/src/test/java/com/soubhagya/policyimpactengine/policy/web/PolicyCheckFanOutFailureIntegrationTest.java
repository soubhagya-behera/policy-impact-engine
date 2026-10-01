package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 16-B/2 — fan-out failure isolation for the manual check (see
 * DECISIONS.md ADR-034 §9). The recommendation service is mocked to
 * fail; the stubbed fetch still produces a NEW_VERSION, and the
 * endpoint must return its successful result with the observation
 * intact — the same healable gap the scheduler accepts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyCheckFanOutFailureIntegrationTest {

	private static final String V1_HTML = "<html><body><h1>Privacy Policy</h1>"
			+ "<p>We collect only the data needed to run your account.</p></body></html>";
	private static final String V2_HTML = "<html><body><h1>Privacy Policy</h1>"
			+ "<p>We collect only the data needed to run your account.</p>"
			+ "<p>We share your location data with third-party advertising partners.</p>"
			+ "</body></html>";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.policy.domain.PolicyRepository policyRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.UserRepository userRepository;

	@MockitoBean
	private PolicyFetcher fetcher;

	@MockitoBean
	private com.soubhagya.policyimpactengine.recommendation.RecommendationService recommendationService;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		jdbcTemplate.update("DELETE FROM notification");
		jdbcTemplate.update("DELETE FROM recommendation");
		jdbcTemplate.update("DELETE FROM impact_assessment_breakdown");
		jdbcTemplate.update("DELETE FROM impact_assessment");
		jdbcTemplate.update("DELETE FROM change_impact");
		jdbcTemplate.update("DELETE FROM change_concept_match");
		jdbcTemplate.update("DELETE FROM policy_change");
		jdbcTemplate.update("DELETE FROM policy_fetch_attempt");
		jdbcTemplate.update("DELETE FROM policy_version");
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
		when(fetcher.fetch(any())).thenAnswer(invocation -> new FetchResult(
				(String) invocation.getArgument(0), 200, "text/html", V1_HTML));
		when(recommendationService.getOrCreateRecommendations(any(), any()))
				.thenThrow(new IllegalStateException("fan-out boom"));
	}

	@Test
	void fanOutFailureStillReturnsSuccessfulCheckResult() throws Exception {
		String token = registerAndLogin("check-fanout-down@example.com");
		UUID id = registerPolicy(token, "Gappy", "https://gappy.example/privacy");
		checkExpect(token, id, "FIRST_VERSION");

		when(fetcher.fetch(any())).thenAnswer(invocation -> new FetchResult(
				(String) invocation.getArgument(0), 200, "text/html", V2_HTML));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("NEW_VERSION"))
				.andExpect(jsonPath("$.versionNumber").value(2))
				.andExpect(jsonPath("$.attemptStatus").value("SUCCESS"));

		assertThat(countWhere("policy_fetch_attempt", "policy_id", id)).isEqualTo(2);
		assertThat(countWhere("policy_version", "policy_id", id)).isEqualTo(2);
		assertThat(tableCount("notification")).isZero();
	}

	private void checkExpect(String token, UUID id, String outcome) throws Exception {
		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value(outcome));
	}

	private UUID registerPolicy(String token, String name, String url) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/policies")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(objectMapper
				.readTree(result.getResponse().getContentAsString()).get("id").asText());
	}

	private String registerAndLogin(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private int countWhere(String table, String column, UUID id) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
				Integer.class, id);
		return count == null ? 0 : count;
	}

	private int tableCount(String table) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM " + table, Integer.class);
		return count == null ? 0 : count;
	}
}
