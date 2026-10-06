package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 18-B — rate-limit proof for the Google completion handoff (see
 * DECISIONS.md ADR-037). {@code POST /api/v1/auth/google/complete}
 * rides the existing anonymous IP-keyed auth-login tier (tiny budget
 * of 3 here); the fourth attempt from the same IP is rejected with the
 * locked 429 shape plus {@code Retry-After}, while a different IP is
 * unaffected. No new limiter mechanism exists.
 */
@SpringBootTest(properties = {
		"rate-limit.enabled=true",
		"rate-limit.auth-max-requests=3",
		"rate-limit.api-max-requests=1000",
		"rate-limit.explanation-max-requests=1000",
		"rate-limit.anonymous-max-requests=1000",
		"rate-limit.refresh-max-requests=1000" })
@AutoConfigureMockMvc
@Testcontainers
class GoogleCompleteRateLimitIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.UserRepository userRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.GoogleCompletionRepository completionRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		completionRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void googleCompleteSharesAuthLoginTier() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.90.1");

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/google/complete")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"code\":\"never-minted-" + i + "\"}"))
					.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(post("/api/v1/auth/google/complete")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"never-minted-throttled\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));
	}

	@Test
	void googleCompleteBudgetsArePerClientIp() throws Exception {
		RequestPostProcessor first = remoteIp("10.0.90.2");
		RequestPostProcessor second = remoteIp("10.0.90.3");

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/google/complete")
							.with(first)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"code\":\"never-minted-" + i + "\"}"))
					.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/v1/auth/google/complete")
						.with(first)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"never-minted-throttled\"}"))
				.andExpect(status().isTooManyRequests());

		mockMvc.perform(post("/api/v1/auth/google/complete")
						.with(second)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"never-minted-other-ip\"}"))
				.andExpect(status().isUnauthorized());
	}

	private static RequestPostProcessor remoteIp(String address) {
		return request -> {
			MockHttpServletRequest servletRequest = (MockHttpServletRequest) request;
			servletRequest.setRemoteAddr(address);
			return request;
		};
	}
}
