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
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.application.AuditVerificationService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 15-A/2 — HTTP endpoint integration tests for anonymous
 * {@code POST /api/v1/auth/logout} with security filters enabled (see
 * DECISIONS.md ADR-031).
 *
 * <p>Proves the no-oracle contract: a live token revokes with exactly
 * one audit row, while unknown/expired/revoked/superseded tokens all
 * yield the same idempotent 204 with no state change and no audit. A
 * superseded presentation never triggers family revocation, repeats
 * converge silently, and the audit chain stays VALID throughout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthLogoutEndpointIntegrationTest {

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
	private AuditEventRepository auditEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private AuditVerificationService verificationService;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void liveTokenLogoutReturns204RevokesTokenAndEmitsOneAudit() throws Exception {
		String refresh = registerAndLogin("logout-ok@example.com");
		UUID ownerId = userIdOf("logout-ok@example.com");

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(liveCount(ownerId)).isZero();
		assertThat(auditCount("AUTH_LOGOUT_SUCCEEDED")).isEqualTo(1);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT actor_user_id, resource_type, resource_id, metadata FROM audit_event "
						+ "WHERE event_type='AUTH_LOGOUT_SUCCEEDED'");
		assertThat(row.get("actor_user_id").toString()).isEqualTo(ownerId.toString());
		assertThat(row.get("resource_type")).isEqualTo("USER");
		assertThat(row.get("resource_id").toString()).isEqualTo(ownerId.toString());
		assertThat(row.get("metadata").toString()).isEqualTo("{}");

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));
	}

	@Test
	void unknownExpiredRevokedTokensReturn204WithoutAuditOrStateChange() throws Exception {
		String refresh = registerAndLogin("logout-noop@example.com");
		UUID ownerId = userIdOf("logout-noop@example.com");
		int liveBefore = liveCount(ownerId);

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		String expiredRaw = generateRawToken();
		Instant created = Instant.now().minusSeconds(7200);
		User user = userRepository.findByEmail("logout-noop@example.com").orElseThrow();
		refreshTokenRepository.saveAndFlush(new RefreshToken(user, sha256Hex(expiredRaw),
				created, created.plusSeconds(3600)));
		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(expiredRaw)))
				.andExpect(status().isNoContent());

		String revokedRaw = registerAndLogin("logout-revoked@example.com");
		User revokedUser = userRepository.findByEmail("logout-revoked@example.com").orElseThrow();
		Instant now = Instant.now();
		refreshTokenRepository.revokeLiveTokensForUser(revokedUser.getId(), now, now);
		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(revokedRaw)))
				.andExpect(status().isNoContent());

		assertThat(liveCount(ownerId)).isEqualTo(liveBefore);
		assertThat(auditCount("AUTH_LOGOUT_SUCCEEDED")).isZero();
		assertThat(auditCount("AUTH_LOGOUT_ALL_SUCCEEDED")).isZero();

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isOk());
	}

	@Test
	void supersededTokenLogoutReturns204WithoutFamilyRevocationOrAudit() throws Exception {
		String first = registerAndLogin("logout-superseded@example.com");
		UUID ownerId = userIdOf("logout-superseded@example.com");

		MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(first)))
				.andExpect(status().isOk())
				.andReturn();
		String second = objectMapper.readTree(rotated.getResponse().getContentAsString())
				.get("refreshToken").asText();

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(first)))
				.andExpect(status().isNoContent());

		assertThat(auditCount("AUTH_LOGOUT_SUCCEEDED")).isZero();
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(second)))
				.andExpect(status().isOk());
		assertThat(liveCount(ownerId)).isEqualTo(1);
	}

	@Test
	void repeatedLogoutIsIdempotentWithSingleAudit() throws Exception {
		String refresh = registerAndLogin("logout-repeat@example.com");

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refresh)))
				.andExpect(status().isNoContent());

		assertThat(auditCount("AUTH_LOGOUT_SUCCEEDED")).isEqualTo(1);
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	@Test
	void missingBlankAndForeignIdentityLogoutReturns400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"   "}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		String refresh = registerAndLogin("logout-shape@example.com");
		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s","userId":"%s","email":"intruder@example.com"}
								""".formatted(refresh, UUID.randomUUID())))
				.andExpect(status().isNoContent());
		assertThat(auditCount("AUTH_LOGOUT_SUCCEEDED")).isEqualTo(1);
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
				.get("refreshToken").asText();
	}

	private UUID userIdOf(String email) {
		return userRepository.findByEmail(email).map(User::getId).orElseThrow();
	}

	private int liveCount(UUID userId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM auth_refresh_token WHERE user_id=? AND revoked_at IS NULL "
						+ "AND expires_at > now()",
				Integer.class, userId);
		return count == null ? 0 : count;
	}

	private int auditCount(String eventType) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_event WHERE event_type=?", Integer.class, eventType);
		return count == null ? 0 : count;
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
