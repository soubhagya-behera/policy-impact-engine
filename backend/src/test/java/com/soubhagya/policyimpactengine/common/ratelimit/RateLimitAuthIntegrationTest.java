package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Phase 13-C — end-to-end rate-limit tests for the authentication tier
 * with security filters enabled (see DECISIONS.md ADR-025).
 *
 * <p>The auth budget is deliberately tiny (3/minute): register plus
 * login consume two tokens, so the flow stays usable while the fourth
 * attempt proves rejection. The auth bucket is keyed by client IP and
 * the limiter is a context singleton, so every test pins its own
 * source address — no test depends on execution order. Other tiers
 * are generous here so only auth counting is exercised. Refill timing
 * is covered by the fixed-clock unit tests.
 */
@SpringBootTest(properties = {
		"rate-limit.enabled=true",
		"rate-limit.auth-max-requests=3",
		"rate-limit.api-max-requests=1000",
		"rate-limit.explanation-max-requests=1000",
		"rate-limit.anonymous-max-requests=1000" })
@AutoConfigureMockMvc
@Testcontainers
class RateLimitAuthIntegrationTest {

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
	void fourthLoginAttemptIsRejectedWith429() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.1.1");
		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"auth-limit@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"email":"auth-limit@example.com","password":"correct-horse-1"}
									"""))
					.andExpect(status().isOk());
		}

		MvcResult rejected = mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"auth-limit@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Too Many Requests"))
				.andReturn();

		String body = rejected.getResponse().getContentAsString();
		assertThatBodyCarriesNoCredentials(body);
	}

	@Test
	void failedLoginsConsumeTheSameBudget() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.1.2");
		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"auth-fail@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"email":"auth-fail@example.com","password":"wrong-password-1"}
									"""))
					.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"auth-fail@example.com","password":"wrong-password-1"}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.title").value("Too Many Requests"));
	}

	@Test
	void throttleDoesNotBreakSubsequentAuthenticatedReads() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.1.3");
		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"auth-read@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"auth-read@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isOk())
				.andReturn();
		String token = objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();

		mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"auth-read@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/me/notifications")
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	@Test
	void exhaustedRegistrationBudgetLeavesLoginUnaffected() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.1.4");
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/register")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"email":"reg-burn-%d@example.com","password":"correct-horse-1"}
									""".formatted(i)))
					.andExpect(status().isCreated());
		}
		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"reg-blocked@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.title").value("Too Many Requests"));

		mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"reg-burn-0@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isOk());
	}

	@Test
	void exhaustedLoginBudgetLeavesRegistrationUnaffected() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.1.5");
		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"login-burn@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.with(ip)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"email":"login-burn@example.com","password":"correct-horse-1"}
									"""))
					.andExpect(status().isOk());
		}
		mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"login-burn@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.title").value("Too Many Requests"));

		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"login-burn-second@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());
	}

	/**
	 * Pins one source address per test. The auth bucket is keyed by IP
	 * and the limiter outlives any single test, so sharing the default
	 * MockMvc address would couple tests through the budget.
	 */
	private static RequestPostProcessor remoteIp(String address) {
		return (MockHttpServletRequest request) -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	private static void assertThatBodyCarriesNoCredentials(String body) {
		org.assertj.core.api.Assertions.assertThat(body)
				.doesNotContain("correct-horse-1");
	}
}
