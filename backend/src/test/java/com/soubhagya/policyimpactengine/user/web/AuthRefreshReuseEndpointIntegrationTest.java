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
import java.util.List;
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
import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 15-B/2 — HTTP endpoint integration tests for refresh-token
 * reuse auditing on {@code POST /api/v1/auth/refresh} with security
 * filters enabled (see DECISIONS.md ADR-032).
 *
 * <p>Proves the first superseded-with-live-family presentation returns
 * the byte-identical uniform 401 with the family dead and exactly one
 * {@code AUTH_REFRESH_REUSE_DETECTED} row (owning actor, USER resource,
 * empty metadata, no secret material), while repeats, zero-live kills,
 * expired, revoked, and unknown presentations return the same 401 with
 * no audit. Successful rotation behavior is unchanged and the audit
 * chain stays VALID throughout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthRefreshReuseEndpointIntegrationTest {

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
	private AuditVerificationService verificationService;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void reuseKillsFamilyReturns401AndEmitsExactlyOneAudit() throws Exception {
		String first = registerAndLogin("reuse-ok@example.com");
		UUID ownerId = userIdOf("reuse-ok@example.com");
		rotateExpectOk(first);

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(first)))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));

		assertThat(liveCount(ownerId)).isZero();
		assertThat(auditCount("AUTH_REFRESH_REUSE_DETECTED")).isEqualTo(1);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT actor_user_id, resource_type, resource_id, metadata FROM audit_event "
						+ "WHERE event_type='AUTH_REFRESH_REUSE_DETECTED'");
		assertThat(row.get("actor_user_id").toString()).isEqualTo(ownerId.toString());
		assertThat(row.get("resource_type")).isEqualTo("USER");
		assertThat(row.get("resource_id").toString()).isEqualTo(ownerId.toString());
		assertThat(row.get("metadata").toString()).isEqualTo("{}");

		List<String> metadata = jdbcTemplate.queryForList(
				"SELECT metadata FROM audit_event WHERE event_type='AUTH_REFRESH_REUSE_DETECTED'",
				String.class);
		assertThat(metadata).hasSize(1);
		assertThat(metadata).noneMatch(value -> value != null
				&& (value.contains(first) || value.contains(sha256Hex(first))));
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	@Test
	void repeatedAndZeroLiveReuseReturn401WithoutNewAudit() throws Exception {
		String first = registerAndLogin("reuse-repeat@example.com");
		rotateExpectOk(first);
		reuseExpect401(first);
		assertThat(auditCount("AUTH_REFRESH_REUSE_DETECTED")).isEqualTo(1);

		reuseExpect401(first);
		reuseExpect401(first);
		assertThat(auditCount("AUTH_REFRESH_REUSE_DETECTED")).isEqualTo(1);

		String lone = loginAndGetRefresh("reuse-repeat@example.com");
		rotateExpectOk(lone);
		reuseExpect401(lone);
		assertThat(auditCount("AUTH_REFRESH_REUSE_DETECTED")).isEqualTo(2);
		reuseExpect401(lone);
		assertThat(auditCount("AUTH_REFRESH_REUSE_DETECTED")).isEqualTo(2);
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	@Test
	void expiredRevokedAndUnknownPresentationsReturn401WithoutAudit() throws Exception {
		String live = registerAndLogin("reuse-silent@example.com");
		UUID ownerId = userIdOf("reuse-silent@example.com");
		int liveBefore = liveCount(ownerId);

		String expiredRaw = generateRawToken();
		Instant created = Instant.now().minusSeconds(7200);
		User user = userRepository.findByEmail("reuse-silent@example.com").orElseThrow();
		refreshTokenRepository.saveAndFlush(new RefreshToken(user, sha256Hex(expiredRaw),
				created, created.plusSeconds(3600)));
		reuseExpect401(expiredRaw);

		String revokedRaw = registerAndLogin("reuse-revoked@example.com");
		User revokedUser = userRepository.findByEmail("reuse-revoked@example.com").orElseThrow();
		Instant now = Instant.now();
		refreshTokenRepository.revokeLiveTokensForUser(revokedUser.getId(), now, now);
		reuseExpect401(revokedRaw);

		reuseExpect401("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

		assertThat(liveCount(ownerId)).isEqualTo(liveBefore);
		assertThat(auditCount("AUTH_REFRESH_REUSE_DETECTED")).isZero();
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(live)))
				.andExpect(status().isOk());
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	private void reuseExpect401(String refreshToken) throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refreshToken)))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));
	}

	private void rotateExpectOk(String refreshToken) throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refreshToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"));
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
