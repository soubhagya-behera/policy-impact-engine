package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 16-C — rate-limit proof for the check history feed (see
 * DECISIONS.md ADR-035 §8). History reads ride the existing
 * authenticated per-user {@code api} tier (tiny budget of 4 here: one
 * policy registration plus three reads); anonymous callers ride the
 * generous anonymous tier into their downstream 401. No new limiter
 * mechanism exists; 429s keep the locked shape with
 * {@code Retry-After}.
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
class PolicyCheckHistoryRateLimitIntegrationTest {

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
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void historyReadsConsumePerUserApiBudget() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.12.1");
		String token = registerAndLogin("history-tier@example.com", ip);
		UUID id = registerPolicy(token, "Tiered", "https://tiered.example/privacy", ip);

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
							.with(ip)
							.header("Authorization", "Bearer " + token))
					.andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));
	}

	@Test
	void anonymousHistoryReadsKeep401Precedence() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.12.2");
		String token = registerAndLogin("history-tier-owner@example.com", ip);
		UUID id = registerPolicy(token, "Gated", "https://gated.example/privacy", ip);

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.with(ip))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
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
