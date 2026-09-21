package com.soubhagya.policyimpactengine.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.JwtProperties;
import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;
import com.soubhagya.policyimpactengine.user.domain.User;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 8B — filter-chain authentication tests with security filters
 * enabled.
 *
 * <p>Exercises the real flow (register → login → Bearer) against
 * Testcontainers PostgreSQL and proves the authenticated request
 * resolves the exact User UUID through a test-only probe controller.
 * The probe lives in the test source set only and accepts no identity
 * input of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class JwtAuthenticationIntegrationTest {

	@TestConfiguration
	static class ProbeConfiguration {

		@Bean
		PrincipalProbeController principalProbeController() {
			return new PrincipalProbeController();
		}
	}

	@RestController
	@RequestMapping("/test/principal")
	static class PrincipalProbeController {

		@GetMapping
		public Map<String, String> principal(Authentication authentication) {
			return Map.of("userId", AuthenticatedUsers.requireUserId(authentication).toString());
		}
	}

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		userRepository.deleteAll();
	}

	@Test
	void registerLoginThenBearerResolvesExactUserUuid() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"jwt-user@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());

		UUID userId = userRepository.findByEmail("jwt-user@example.com")
				.map(User::getId)
				.orElseThrow();

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"jwt-user@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andReturn();
		String token = objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
		assertThat(token).isNotBlank();

		mockMvc.perform(get("/test/principal").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(userId.toString()));
	}

	@Test
	void probeIgnoresClientSuppliedUserId() throws Exception {
		String token = registerAndLogin("override-user@example.com");
		UUID actual = userRepository.findByEmail("override-user@example.com")
				.map(User::getId)
				.orElseThrow();

		mockMvc.perform(get("/test/principal")
						.queryParam("userId", UUID.randomUUID().toString())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(actual.toString()));
	}

	@Test
	void missingTokenReturns401Problem() throws Exception {
		mockMvc.perform(get("/test/principal"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void invalidAndMalformedTokensReturn401Problem() throws Exception {
		mockMvc.perform(get("/test/principal").header("Authorization", "Bearer invalid.token.here"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/test/principal").header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/test/principal").header("Authorization", "Token abc"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void wrongSignatureTokenReturns401Problem() throws Exception {
		String token = registerAndLogin("sig-user@example.com");
		UUID userId = userRepository.findByEmail("sig-user@example.com")
				.map(User::getId)
				.orElseThrow();
		assertThat(token).isNotBlank();

		JwtService otherKey = new JwtService(
				new JwtProperties("different-test-secret-that-is-long-enough-0000", null),
				Clock.systemUTC());
		String forged = otherKey.issueAccessToken(userId);

		mockMvc.perform(get("/test/principal").header("Authorization", "Bearer " + forged))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void expiredTokenReturns401Problem() throws Exception {
		UUID userId = registerUser("expired-user@example.com");
		JwtService pastIssuer = new JwtService(
				jwtProperties, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
		String expired = pastIssuer.issueAccessToken(userId);

		mockMvc.perform(get("/test/principal").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void loginRejectsBadCredentialsUniformly() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"reject-user@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());

		String wrongPassword = """
				{"email":"reject-user@example.com","password":"wrong-password-2"}
				""";
		String unknownEmail = """
				{"email":"nobody-here@example.com","password":"correct-horse-1"}
				""";

		for (String body : new String[] { wrongPassword, unknownEmail }) {
			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
					.andExpect(status().isUnauthorized())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.title").value("Unauthenticated"))
					.andExpect(jsonPath("$.detail").value("Invalid email or password"));
		}
	}

	@Test
	void policiesRequireAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"public-user@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/v1/policies"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		String token;
		{
			MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"email":"public-user@example.com","password":"correct-horse-1"}
									"""))
					.andExpect(status().isOk())
					.andReturn();
			token = objectMapper.readTree(login.getResponse().getContentAsString())
					.get("accessToken").asText();
		}
		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
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

	private UUID registerUser(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());
		return userRepository.findByEmail(email).map(User::getId).orElseThrow();
	}
}
