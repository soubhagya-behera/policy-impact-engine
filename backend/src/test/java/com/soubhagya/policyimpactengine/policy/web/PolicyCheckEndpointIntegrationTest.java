package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

import com.soubhagya.policyimpactengine.audit.application.AuditVerificationService;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetchException;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 16-B/2 — end-to-end proof for {@code POST
 * /api/v1/policies/{policyId}/check} terminal outcomes with security
 * filters enabled (see DECISIONS.md ADR-034). HTTP fetching is stubbed;
 * everything else (claiming, pipeline, persistence, fan-out) is real
 * against Testcontainers PostgreSQL.
 *
 * <p>Proves terminal 200 results for all three outcomes with exact
 * field mapping, fan-out through the existing path, unchanged
 * scheduling state, uniform 502 with intact FAILED history, and audit
 * silence with a VALID chain throughout. Rejection shapes and claim
 * collisions live in {@code PolicyCheckRejectionIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyCheckEndpointIntegrationTest {

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
	private PolicyRepository policyRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AuditVerificationService verificationService;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.UserRepository userRepository;

	@MockitoBean
	private PolicyFetcher fetcher;

	@BeforeEach
	void clean() {
		Mockito.clearInvocations(fetcher);
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
		stubHtml(V1_HTML);
	}

	@Test
	void firstVersionReturnsTerminalSuccessResult() throws Exception {
		String token = registerAndLogin("check-first@example.com");
		UUID id = registerPolicy(token, "First", "https://first.example/privacy");

		MvcResult result = mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policyId").value(id.toString()))
				.andExpect(jsonPath("$.outcome").value("FIRST_VERSION"))
				.andExpect(jsonPath("$.versionNumber").value(1))
				.andExpect(jsonPath("$.changeCount").value(0))
				.andExpect(jsonPath("$.attemptStatus").value("SUCCESS"))
				.andExpect(jsonPath("$.contentHash").isNotEmpty())
				.andReturn();

		String hash = body(result).get("contentHash").asText();
		assertThat(versionHash(id, 1)).isEqualTo(hash);
		assertThat(attemptRows(id)).containsExactly("SUCCESS:MANUAL");
		assertThat(versionCount(id)).isEqualTo(1);
	}

	@Test
	void unchangedReturnsSkippedUnchangedWithoutNewVersion() throws Exception {
		String token = registerAndLogin("check-unchanged@example.com");
		UUID id = registerPolicy(token, "Same", "https://same.example/privacy");
		checkExpect(token, id, "FIRST_VERSION", 1, "SUCCESS");

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("UNCHANGED"))
				.andExpect(jsonPath("$.versionNumber").value(1))
				.andExpect(jsonPath("$.changeCount").value(0))
				.andExpect(jsonPath("$.attemptStatus").value("SKIPPED_UNCHANGED"));

		assertThat(versionCount(id)).isEqualTo(1);
		assertThat(attemptRows(id)).containsExactly("SUCCESS:MANUAL", "SKIPPED_UNCHANGED:MANUAL");
	}

	@Test
	void newVersionFansOutAndLeavesSchedulingUntouched() throws Exception {
		String token = registerAndLogin("check-new@example.com");
		UUID id = registerPolicy(token, "Evolving", "https://evolving.example/privacy");
		Instant scheduled = policyRepository.findById(id).orElseThrow().getNextCheckAt();
		checkExpect(token, id, "FIRST_VERSION", 1, "SUCCESS");
		long auditsBefore = auditEventRepository.count();
		stubHtml(V2_HTML);

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("NEW_VERSION"))
				.andExpect(jsonPath("$.versionNumber").value(2))
				.andExpect(jsonPath("$.attemptStatus").value("SUCCESS"))
				.andExpect(jsonPath("$.changeCount").isNumber());

		assertThat(changeCount(id)).isPositive();
		assertThat(policyRepository.findById(id).orElseThrow().getNextCheckAt())
				.isEqualTo(scheduled);
		assertThat(tableCount("impact_assessment")).isEqualTo(1);
		assertThat(tableCount("recommendation")).isPositive();
		assertThat(tableCount("notification")).isEqualTo(1);
		assertThat(auditEventRepository.count()).isEqualTo(auditsBefore);
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	@Test
	void failedFetchReturns502WithFailedAttemptAndReschedule() throws Exception {
		String token = registerAndLogin("check-failing@example.com");
		UUID id = registerPolicy(token, "Flaky", "https://flaky.example/privacy");
		Instant scheduled = policyRepository.findById(id).orElseThrow().getNextCheckAt();
		when(fetcher.fetch(anyString()))
				.thenThrow(new PolicyFetchException("connection reset", null, true));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadGateway())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Bad Gateway"))
				.andExpect(jsonPath("$.detail").value("connection reset"));

		assertThat(attemptRows(id)).containsExactly("FAILED:MANUAL");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT failure_kind FROM policy_fetch_attempt WHERE policy_id = ?",
				String.class, id)).isEqualTo("TRANSIENT");
		assertThat(policyRepository.findById(id).orElseThrow().getNextCheckAt())
				.isNotEqualTo(scheduled);
		assertThat(versionCount(id)).isZero();
	}

	private void checkExpect(String token, UUID id, String outcome, int version,
			String attemptStatus) throws Exception {
		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value(outcome))
				.andExpect(jsonPath("$.versionNumber").value(version))
				.andExpect(jsonPath("$.attemptStatus").value(attemptStatus));
	}

	private void stubHtml(String html) {
		when(fetcher.fetch(anyString())).thenAnswer(invocation -> new FetchResult(
				(String) invocation.getArgument(0), 200, "text/html", html));
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

	private JsonNode body(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String versionHash(UUID policyId, int versionNumber) {
		return jdbcTemplate.queryForObject(
				"SELECT content_hash FROM policy_version WHERE policy_id = ? AND version_number = ?",
				String.class, policyId, versionNumber);
	}

	private int versionCount(UUID policyId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM policy_version WHERE policy_id = ?", Integer.class, policyId);
		return count == null ? 0 : count;
	}

	private int changeCount(UUID policyId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM policy_change pc JOIN policy_version pv ON "
						+ "pc.new_version_id = pv.id WHERE pv.policy_id = ?",
				Integer.class, policyId);
		return count == null ? 0 : count;
	}

	private List<String> attemptRows(UUID policyId) {
		return jdbcTemplate.queryForList(
				"SELECT status || ':' || trigger FROM policy_fetch_attempt WHERE policy_id = ? "
						+ "ORDER BY started_at, id",
				String.class, policyId);
	}

	private int tableCount(String table) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM " + table, Integer.class);
		return count == null ? 0 : count;
	}
}
