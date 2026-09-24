package com.soubhagya.policyimpactengine.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 13-D — exact-string security-header tests with security
 * filters enabled (see DECISIONS.md ADR-027).
 *
 * <p>Proves the locked literals on 200, 401, 404, and 429 paths, and
 * that HSTS is absent on plain HTTP but present with the exact
 * spaceless literal on secure requests. Rate limiting is re-enabled
 * here with a shrunken API budget so the 429 path is reachable; every
 * other suite keeps the shared disabled limiter.
 */
@SpringBootTest(properties = {
		"rate-limit.enabled=true",
		"rate-limit.auth-max-requests=100",
		"rate-limit.api-max-requests=3",
		"rate-limit.explanation-max-requests=100",
		"rate-limit.anonymous-max-requests=1000" })
@AutoConfigureMockMvc
@Testcontainers
class SecurityHeadersTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void okResponseCarriesExactHeadersWithoutHstsOnPlainHttp() throws Exception {
		String token = registerAndLogin("headers-ok@example.com");

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'"))
				.andExpect(header().string("Permissions-Policy",
						"geolocation=(), microphone=(), camera=()"))
				.andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
				.andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
				.andExpect(header().doesNotExist("Strict-Transport-Security"));
	}

	@Test
	void secureRequestCarriesExactHstsLiteral() throws Exception {
		String token = registerAndLogin("headers-hsts@example.com");

		mockMvc.perform(get("/api/v1/policies")
						.secure(true)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(header().string("Strict-Transport-Security",
						"max-age=31536000; includeSubDomains"));
	}

	@Test
	void unauthorizedResponseCarriesHeaders() throws Exception {
		mockMvc.perform(get("/api/v1/policies"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'"))
				.andExpect(header().string("Permissions-Policy",
						"geolocation=(), microphone=(), camera=()"))
				.andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
				.andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"));
	}

	@Test
	void notFoundResponseCarriesHeaders() throws Exception {
		String token = registerAndLogin("headers-404@example.com");

		mockMvc.perform(get("/api/v1/policies/{id}", UUID.randomUUID())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'"));
	}

	@Test
	void rateLimitedResponseCarriesHeaders() throws Exception {
		String token = registerAndLogin("headers-429@example.com");

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/api/v1/policies")
							.header("Authorization", "Bearer " + token))
					.andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.title").value("Too Many Requests"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'"))
				.andExpect(header().string("Permissions-Policy",
						"geolocation=(), microphone=(), camera=()"))
				.andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
				.andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"));
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
