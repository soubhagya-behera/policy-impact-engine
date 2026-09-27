package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 14-A/3c — end-to-end rate-limit tests for the auth-refresh tier
 * with security filters enabled (see DECISIONS.md ADR-029 §7).
 *
 * <p>The refresh budget is deliberately tiny (3/minute) while every
 * other tier stays generous, so only refresh counting is exercised.
 * The refresh bucket is keyed by client IP and the limiter is a
 * context singleton, so every test pins its own source address — no
 * test depends on execution order. Refresh tokens are single-use, so
 * successful refreshes chain through each returned successor.
 */
@SpringBootTest(properties = {
		"rate-limit.enabled=true",
		"rate-limit.auth-max-requests=1000",
		"rate-limit.api-max-requests=1000",
		"rate-limit.explanation-max-requests=1000",
		"rate-limit.anonymous-max-requests=1000",
		"rate-limit.refresh-max-requests=3" })
@AutoConfigureMockMvc
@Testcontainers
class RateLimitRefreshIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AuditEventRepository auditEventRepository;

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
	void threeRefreshesAllowedThen429BeforeValidationAndRotation() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.2.1");
		String refresh = registerAndLogin("refresh-budget@example.com", ip);

		for (int i = 0; i < 3; i++) {
			MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"refreshToken":"%s"}
									""".formatted(refresh)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.tokenType").value("Bearer"))
					.andReturn();
			refresh = objectMapper.readTree(rotated.getResponse().getContentAsString())
					.get("refreshToken").asText();
		}

		mockMvc.perform(post("/api/v1/auth/refresh")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.title").value("Too Many Requests"));
	}

	@Test
	void failedRefreshAttemptsConsumeTheSameBudget() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.2.2");
		registerAndLogin("refresh-fail-budget@example.com", ip);

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/refresh")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
									"""))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.title").value("Unauthenticated"));
		}

		mockMvc.perform(post("/api/v1/auth/refresh")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));
	}

	@Test
	void exhaustedRefreshBudgetLeavesLoginAndRegistrationUnaffected() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.2.3");
		registerAndLogin("refresh-isolated@example.com", ip);

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/refresh")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
									"""))
					.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/v1/auth/refresh")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isTooManyRequests());

		mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"refresh-isolated@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"refresh-isolated-second@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());
	}

	@Test
	void differentIpGetsSeparateRefreshBudget() throws Exception {
		RequestPostProcessor burned = remoteIp("10.0.2.4");
		RequestPostProcessor fresh = remoteIp("10.0.2.5");
		registerAndLogin("refresh-burned@example.com", burned);

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/refresh")
							.with(burned)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
									"""))
					.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/v1/auth/refresh")
						.with(burned)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isTooManyRequests());

		String refresh = registerAndLogin("refresh-fresh@example.com", fresh);
		MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
						.with(fresh)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andReturn();
		assertThat(objectMapper.readTree(rotated.getResponse().getContentAsString())
				.get("refreshToken").asText()).isNotEqualTo(refresh);
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
				.get("refreshToken").asText();
	}

	/**
	 * Pins one source address per test. The refresh bucket is keyed by
	 * IP and the limiter outlives any single test, so sharing the
	 * default MockMvc address would couple tests through the budget.
	 */
	private static RequestPostProcessor remoteIp(String address) {
		return (MockHttpServletRequest request) -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
