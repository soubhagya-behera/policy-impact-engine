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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 15-A/2 — end-to-end rate-limit tests for logout (see DECISIONS.md
 * ADR-031 §10).
 *
 * <p>{@code POST /api/v1/auth/logout} shares the existing anonymous
 * {@code auth-refresh} IP bucket with refresh (tiny 3/minute budget
 * here while every other tier stays generous); {@code POST
 * /api/v1/auth/logout-all} rides the existing per-user {@code api}
 * tier (tiny budget of 2 here). No new limiter mechanism exists, 429s
 * keep the locked problem shape with {@code Retry-After}, and
 * {@code X-Forwarded-For} stays untrusted. Every test pins its own
 * source address because the limiter outlives any single test.
 */
@SpringBootTest(properties = {
		"rate-limit.enabled=true",
		"rate-limit.auth-max-requests=1000",
		"rate-limit.api-max-requests=2",
		"rate-limit.explanation-max-requests=1000",
		"rate-limit.anonymous-max-requests=1000",
		"rate-limit.refresh-max-requests=3" })
@AutoConfigureMockMvc
@Testcontainers
class RateLimitLogoutIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private AuditEventRepository auditEventRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void logoutSharesRefreshBudgetAnd429PrecedesValidation() throws Exception {
		org.springframework.test.web.servlet.request.RequestPostProcessor ip = remoteIp("10.0.9.1");

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/logout")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
									"""))
					.andExpect(status().isNoContent());
		}

		mockMvc.perform(post("/api/v1/auth/logout")
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
	}

	@Test
	void refreshAndLogoutConsumeTheSameBucket() throws Exception {
		org.springframework.test.web.servlet.request.RequestPostProcessor ip = remoteIp("10.0.9.2");

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/api/v1/auth/refresh")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
									"""))
					.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/v1/auth/logout")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isNoContent());

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
	void forwardedForHeaderIsIgnoredForLogoutBudget() throws Exception {
		org.springframework.test.web.servlet.request.RequestPostProcessor ip = remoteIp("10.0.9.3");

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/logout")
							.with(ip)
							.header("X-Forwarded-For", "198.51.100." + i)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
									"""))
					.andExpect(status().isNoContent());
		}
		mockMvc.perform(post("/api/v1/auth/logout")
						.with(ip)
						.header("X-Forwarded-For", "203.0.113.99")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void logoutAllRidesThePerUserApiTier() throws Exception {
		org.springframework.test.web.servlet.request.RequestPostProcessor ip = remoteIp("10.0.9.4");
		String access = registerAndLogin("logout-tier@example.com", ip);

		mockMvc.perform(post("/api/v1/auth/logout-all")
						.with(ip)
						.header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/auth/logout-all")
						.with(ip)
						.header("Authorization", "Bearer " + access))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/auth/logout-all")
						.with(ip)
						.header("Authorization", "Bearer " + access))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));
	}

	private String registerAndLogin(String email,
			org.springframework.test.web.servlet.request.RequestPostProcessor ip) throws Exception {
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

	private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteIp(
			String address) {
		return (MockHttpServletRequest request) -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
