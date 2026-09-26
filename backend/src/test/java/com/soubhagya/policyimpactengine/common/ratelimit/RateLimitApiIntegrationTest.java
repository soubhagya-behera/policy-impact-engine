package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 13-C — end-to-end rate-limit tests for the general API,
 * explanation, and anonymous tiers with security filters enabled
 * (see DECISIONS.md ADR-025).
 *
 * <p>Test properties shrink the windows so rejection is reachable
 * without slow tests; refill timing itself is covered by the
 * fixed-clock unit tests. Every test uses fresh users (distinct
 * limiter keys), so no test depends on execution order. The auth tier
 * lives in {@link RateLimitAuthIntegrationTest} with its own budget.
 */
@SpringBootTest(properties = {
		"rate-limit.enabled=true",
		"rate-limit.auth-max-requests=100",
		"rate-limit.api-max-requests=3",
		"rate-limit.explanation-max-requests=2",
		"rate-limit.anonymous-max-requests=1000" })
@AutoConfigureMockMvc
@Testcontainers
class RateLimitApiIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AuditEventRepository auditEventRepository;

	// Phase 14-A/3a: successful logins persist refresh rows referencing
	// users, so refresh rows go before user deletion.
	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void fourthAuthenticatedRequestIsRejectedWith429() throws Exception {
		String token = registerAndLogin("api-limit@example.com");

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/api/v1/me/notifications")
							.header("Authorization", "Bearer " + token))
					.andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Too Many Requests"))
				.andExpect(jsonPath("$.detail").exists());
	}

	@Test
	void limitsArePerUser() throws Exception {
		String tokenA = registerAndLogin("api-iso-a@example.com");
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/api/v1/me/notifications")
							.header("Authorization", "Bearer " + tokenA))
					.andExpect(status().isOk());
		}
		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isTooManyRequests());

		String tokenB = registerAndLogin("api-iso-b@example.com");
		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isOk());
	}

	@Test
	void explanationTierIsSeparateFromGeneralApi() throws Exception {
		String token = registerAndLogin("api-explain@example.com");
		UUID missing = UUID.randomUUID();

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/api/v1/me/impact-assessments/{id}/explanation", missing)
							.header("Authorization", "Bearer " + token))
					.andExpect(status().isNotFound());
		}
		mockMvc.perform(post("/api/v1/me/impact-assessments/{id}/explanation", missing)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));

		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	@Test
	void anonymousRequestsStillReturn401() throws Exception {
		mockMvc.perform(get("/api/v1/me/notifications"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("page", "-1")
						.queryParam("size", "500"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
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
		String token = objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
		assertThat(token).isNotBlank();
		return token;
	}
}
