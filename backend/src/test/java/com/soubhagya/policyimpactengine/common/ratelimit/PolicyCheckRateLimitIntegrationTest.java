package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 16-B/2 — rate-limit proof for the manual check (see DECISIONS.md
 * ADR-034 §8). The check rides the existing authenticated per-user
 * {@code api} tier (tiny budget of 4 here: one registration plus three
 * checks); anonymous callers ride the
 * generous anonymous tier into their downstream 401. No new limiter
 * mechanism exists; 429s keep the locked shape with
 * {@code Retry-After}. HTTP fetching is stubbed; the limiter is a
 * context singleton, so every test pins its own source address.
 */
@SpringBootTest(properties = {
		"rate-limit.enabled=true",
		"rate-limit.auth-max-requests=1000",
		"rate-limit.api-max-requests=4",
		"rate-limit.explanation-max-requests=1000",
		"rate-limit.anonymous-max-requests=1000",
		"rate-limit.refresh-max-requests=1000" })
@AutoConfigureMockMvc
@Testcontainers
class PolicyCheckRateLimitIntegrationTest {

	private static final String HTML = "<html><body><h1>Privacy Policy</h1>"
			+ "<p>We collect only the data needed to run your account.</p></body></html>";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.UserRepository userRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.policy.domain.PolicyRepository policyRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private PolicyFetcher fetcher;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		jdbcTemplate.update("DELETE FROM policy_fetch_attempt");
		jdbcTemplate.update("DELETE FROM policy_version");
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
		when(fetcher.fetch(anyString())).thenAnswer(invocation -> new FetchResult(
				(String) invocation.getArgument(0), 200, "text/html", HTML));
	}

	@Test
	void authenticatedChecksConsumePerUserApiBudget() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.11.1");
		String token = registerAndLogin("check-tier@example.com", ip);
		UUID id = registerPolicy(token, "Tiered", "https://tiered.example/privacy", ip);

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
							.with(ip)
							.header("Authorization", "Bearer " + token))
					.andExpect(status().isOk());
		}

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));
	}

	@Test
	void anonymousChecksKeep401Precedence() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.11.2");
		String token = registerAndLogin("check-tier-owner@example.com", ip);
		UUID id = registerPolicy(token, "Gated", "https://gated.example/privacy", ip);

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.with(ip))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.with(ip)
						.header("Authorization", "Bearer invalid.token.here"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	private UUID registerPolicy(String token, String name, String url,
			RequestPostProcessor ip) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/policies")
						.with(ip)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(objectMapper
				.readTree(result.getResponse().getContentAsString()).get("id").asText());
	}

	private String registerAndLogin(String email, RequestPostProcessor ip) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private static RequestPostProcessor remoteIp(String address) {
		return (MockHttpServletRequest request) -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
