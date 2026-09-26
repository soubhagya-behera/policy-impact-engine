package com.soubhagya.policyimpactengine.common.web.cors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 13-D — opt-in CORS tests with one exact origin allowlisted
 * (see DECISIONS.md ADR-027).
 *
 * <p>Proves exact-origin matching (allowlisted origins are echoed,
 * others receive nothing), actual requests stay authenticated, CORS
 * never applies to {@code /actuator/**}, and the preflight-only
 * rate-limit bypass: genuine preflights pass after the API budget is
 * exhausted while arbitrary {@code OPTIONS} and further
 * non-preflight requests are still rejected with 429.
 */
@SpringBootTest(properties = {
		"app.cors.allowed-origins=https://app.example.com",
		"rate-limit.enabled=true",
		"rate-limit.auth-max-requests=100",
		"rate-limit.api-max-requests=1",
		"rate-limit.explanation-max-requests=100",
		"rate-limit.anonymous-max-requests=1000" })
@AutoConfigureMockMvc
@Testcontainers
class CorsAllowedOriginTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	// Phase 14-A/3a: refresh-first (user FK).
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
	void preflightFromAllowlistedOriginIsEchoedExactly() throws Exception {
		mockMvc.perform(options("/api/v1/policies")
						.header("Origin", "https://app.example.com")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin",
						"https://app.example.com"));
	}

	@Test
	void preflightFromUnknownOriginReceivesNothing() throws Exception {
		mockMvc.perform(options("/api/v1/policies")
						.header("Origin", "https://evil.example.com")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void actualRequestFromAllowlistedOriginStaysAuthenticated() throws Exception {
		String token = registerAndLogin("cors-actual@example.com");

		mockMvc.perform(get("/api/v1/policies")
						.header("Origin", "https://app.example.com")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin",
						"https://app.example.com"));

		mockMvc.perform(get("/api/v1/policies")
						.header("Origin", "https://app.example.com"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void actuatorResponsesNeverCarryCorsHeaders() throws Exception {
		String token = registerAndLogin("cors-actuator@example.com");

		mockMvc.perform(get("/actuator/health")
						.header("Origin", "https://app.example.com")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void onlyGenuinePreflightsBypassRateLimiting() throws Exception {
		String token = registerAndLogin("cors-bypass@example.com");

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(options("/api/v1/policies")
						.header("Origin", "https://app.example.com")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.title").value("Too Many Requests"));

		mockMvc.perform(options("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isTooManyRequests());
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
}
