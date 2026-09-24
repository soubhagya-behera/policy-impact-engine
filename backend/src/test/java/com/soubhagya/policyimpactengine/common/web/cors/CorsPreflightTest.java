package com.soubhagya.policyimpactengine.common.web.cors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 13-D — deny-by-default CORS tests with security filters
 * enabled (see DECISIONS.md ADR-027).
 *
 * <p>With no configured origins (the default) no CORS headers are
 * ever emitted, browser preflights on {@code /api/**} pass
 * authentication without credentials while actual requests stay
 * JWT-gated, and actuator paths stay gated even for preflights. The
 * shared test profile applies (limiter disabled).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CorsPreflightTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Test
	void preflightWithoutConfiguredOriginsEmitsNoCorsHeaders() throws Exception {
		mockMvc.perform(options("/api/v1/policies")
						.header("Origin", "https://app.example.com")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void actualRequestStillRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/policies")
						.header("Origin", "https://app.example.com"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void actuatorPreflightGrantsNoCorsAndActualRequestsStayGated() throws Exception {
		// Spring's CorsFilter answers the preflight itself with an
		// empty 200 before authorization: no CORS grant is emitted,
		// so browsers still block cross-origin use and no data leaks.
		mockMvc.perform(options("/actuator/health")
						.header("Origin", "https://app.example.com")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

		// Any non-preflight actuator request without credentials is
		// still rejected by authentication.
		mockMvc.perform(options("/actuator/health")
						.header("Origin", "https://app.example.com"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}
}
