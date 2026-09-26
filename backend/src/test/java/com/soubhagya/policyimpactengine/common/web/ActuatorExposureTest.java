package com.soubhagya.policyimpactengine.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 13-D — minimal Actuator exposure tests with security filters
 * enabled (see DECISIONS.md ADR-027).
 *
 * <p>Proves only {@code health} and {@code info} resolve, health
 * details are never shown, and both endpoints stay authenticated:
 * anonymous callers receive the standard 401 problem, while
 * {@code env}, {@code metrics}, and {@code beans} are 404 even with
 * credentials. The shared test profile applies (limiter disabled,
 * health/info exposure from test resources).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ActuatorExposureTest {

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

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void healthWithoutAuthenticationReturns401Problem() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void healthWithAuthenticationReturnsUpWithoutDetails() throws Exception {
		String token = registerAndLogin("actuator-health@example.com");

		MvcResult result = mockMvc.perform(get("/actuator/health")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andReturn();

		assertThat(result.getResponse().getContentAsString())
				.doesNotContain("components");
	}

	@Test
	void infoWithAuthenticationIsReachable() throws Exception {
		String token = registerAndLogin("actuator-info@example.com");

		mockMvc.perform(get("/actuator/info")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	@Test
	void infoWithoutAuthenticationReturns401() throws Exception {
		mockMvc.perform(get("/actuator/info"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void sensitiveEndpointsStayUnexposed() throws Exception {
		String token = registerAndLogin("actuator-hidden@example.com");

		mockMvc.perform(get("/actuator/env")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/metrics")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/beans")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound());
	}

	@Test
	void actuatorPreflightIsNotGrantedCors() throws Exception {
		mockMvc.perform(options("/actuator/health")
						.header("Origin", "https://app.example.com")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
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
		return new tools.jackson.databind.ObjectMapper()
				.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
	}
}
