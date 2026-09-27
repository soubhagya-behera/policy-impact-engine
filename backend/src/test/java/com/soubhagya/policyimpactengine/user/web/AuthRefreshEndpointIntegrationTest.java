package com.soubhagya.policyimpactengine.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
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

import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 14-A/3b — HTTP endpoint integration tests for
 * {@code POST /api/v1/auth/refresh} with security filters enabled.
 *
 * <p>Exercises the real flow (register → login → refresh) against
 * Testcontainers PostgreSQL and proves the endpoint contract: success
 * rotates, the old token dies, every invalid family maps to the one
 * fixed 401, validation maps to 400, a bad Bearer header never blocks
 * a valid refresh, and caller-supplied identity can never override
 * the rotation owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthRefreshEndpointIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void successfulRefreshReturnsNewTokensAndOldTokenDies() throws Exception {
		String refresh = registerAndLogin("refresh-ok@example.com");
		UUID ownerId = userRepository.findByEmail("refresh-ok@example.com")
				.map(User::getId).orElseThrow();

		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshExpiresIn").value(2_592_000))
				.andExpect(jsonPath("$.tokenHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.userId").doesNotExist())
				.andReturn();

		JsonNode body = objectMapper.readTree(refreshed.getResponse().getContentAsString());
		String newAccess = body.get("accessToken").asText();
		String newRefresh = body.get("refreshToken").asText();
		assertThat(newRefresh).isNotEqualTo(refresh);
		assertThat(jwtService.parseUserId(newAccess)).isEqualTo(ownerId);
		assertThat(body.toString()).doesNotContain(refresh);

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(newRefresh)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));
	}

	@Test
	void unknownExpiredRevokedAndReusedTokensMapToSame401() throws Exception {
		String refresh = registerAndLogin("refresh-fail@example.com");
		User user = userRepository.findByEmail("refresh-fail@example.com").orElseThrow();

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));

		String expiredRaw = generateRawToken();
		Instant created = Instant.now().minusSeconds(7200);
		refreshTokenRepository.saveAndFlush(
				new RefreshToken(user, sha256Hex(expiredRaw),
						created, created.plusSeconds(3600)));
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(expiredRaw)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));

		String revokedRaw = registerAndLogin("refresh-revoked@example.com");
		User revokedUser = userRepository.findByEmail("refresh-revoked@example.com").orElseThrow();
		Instant now = Instant.now();
		refreshTokenRepository.revokeLiveTokensForUser(revokedUser.getId(), now, now);
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(revokedRaw)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));
	}

	@Test
	void missingAndBlankRefreshTokenReturn400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":""}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"   "}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));
	}

	@Test
	void malformedBearerHeaderDoesNotBlockValidRefresh() throws Exception {
		String refresh = registerAndLogin("refresh-bearer@example.com");

		mockMvc.perform(post("/api/v1/auth/refresh")
						.header("Authorization", "Bearer invalid.token.here")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"));

		String second = loginAndGetRefresh("refresh-bearer@example.com");
		mockMvc.perform(post("/api/v1/auth/refresh")
						.header("Authorization", "Token abc")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(second)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"));
	}

	@Test
	void callerSuppliedIdentityCannotOverrideRotationOwner() throws Exception {
		String refresh = registerAndLogin("refresh-owner@example.com");
		registerAndLogin("refresh-intruder@example.com");
		UUID intruderId = userRepository.findByEmail("refresh-intruder@example.com")
				.map(User::getId).orElseThrow();
		UUID ownerId = userRepository.findByEmail("refresh-owner@example.com")
				.map(User::getId).orElseThrow();

		MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s","userId":"%s","email":"refresh-intruder@example.com","username":"intruder"}
								""".formatted(refresh, intruderId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andReturn();

		String access = objectMapper.readTree(result.getResponse().getContentAsString())
				.get("accessToken").asText();
		assertThat(jwtService.parseUserId(access)).isEqualTo(ownerId);
	}

	@Test
	void loginStillSucceedsAfterRefreshSlice() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"refresh-login@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"refresh-login@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.refreshToken").isNotEmpty());
	}

	private String registerAndLogin(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());
		return loginAndGetRefresh(email);
	}

	private String loginAndGetRefresh(String email) throws Exception {
		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString())
				.get("refreshToken").asText();
	}

	private static String generateRawToken() {
		byte[] entropy = new byte[32];
		new SecureRandom().nextBytes(entropy);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
	}

	private static String sha256Hex(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (java.security.NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
	}
}
