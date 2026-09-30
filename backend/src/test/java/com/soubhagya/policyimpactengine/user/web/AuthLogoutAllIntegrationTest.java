package com.soubhagya.policyimpactengine.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 15-A/2 — HTTP endpoint integration tests for authenticated
 * {@code POST /api/v1/auth/logout-all} with security filters enabled
 * (see DECISIONS.md ADR-031).
 *
 * <p>Proves principal-only identity, bulk revocation of the caller's
 * live family with exactly one audit row, user isolation, idempotent
 * zero-live success, existing 401 behavior, and that access JWTs stay
 * usable until expiry because logout touches refresh state only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthLogoutAllIntegrationTest {

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
	void logoutAllRevokesEveryLiveSessionWithOneAudit() throws Exception {
		Session first = registerAndLogin("logout-all@example.com");
		Session second = login("logout-all@example.com");

		mockMvc.perform(post("/api/v1/auth/logout-all")
						.header("Authorization", "Bearer " + first.accessToken()))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(liveCount(first.userId())).isZero();
		assertThat(auditCount("AUTH_LOGOUT_ALL_SUCCEEDED")).isEqualTo(1);
		assertThat(auditCount("AUTH_LOGOUT_SUCCEEDED")).isZero();

		for (String refresh : new String[] { first.refreshToken(), second.refreshToken() }) {
			mockMvc.perform(post("/api/v1/auth/refresh")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"refreshToken":"%s"}
									""".formatted(refresh)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.detail").value("Invalid refresh token"));
		}
	}

	@Test
	void logoutAllWithZeroLiveRowsIs204WithoutAudit() throws Exception {
		Session session = registerAndLogin("logout-all-empty@example.com");

		mockMvc.perform(post("/api/v1/auth/logout-all")
						.header("Authorization", "Bearer " + session.accessToken()))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/auth/logout-all")
						.header("Authorization", "Bearer " + session.accessToken()))
				.andExpect(status().isNoContent());

		assertThat(auditCount("AUTH_LOGOUT_ALL_SUCCEEDED")).isEqualTo(1);
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	@Test
	void logoutAllIsolatesUsers() throws Exception {
		Session owner = registerAndLogin("logout-all-owner@example.com");
		Session other = registerAndLogin("logout-all-other@example.com");

		mockMvc.perform(post("/api/v1/auth/logout-all")
						.header("Authorization", "Bearer " + owner.accessToken()))
				.andExpect(status().isNoContent());

		assertThat(liveCount(other.userId())).isEqualTo(1);
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(other.refreshToken())))
				.andExpect(status().isOk());
	}

	@Test
	void logoutAllWithoutOrWithBadJwtReturns401() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout-all"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(post("/api/v1/auth/logout-all")
						.header("Authorization", "Bearer invalid.token.here"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		assertThat(auditCount("AUTH_LOGOUT_ALL_SUCCEEDED")).isZero();
	}

	@Test
	void accessJwtRemainsUsableAfterLogoutAllUntilExpiry() throws Exception {
		Session session = registerAndLogin("logout-all-jwt@example.com");

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + session.accessToken()))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/auth/logout-all")
						.header("Authorization", "Bearer " + session.accessToken()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + session.accessToken()))
				.andExpect(status().isOk());
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	private record Session(UUID userId, String accessToken, String refreshToken) {
	}

	private Session registerAndLogin(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());
		return login(email);
	}

	private Session login(String email) throws Exception {
		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		UUID userId = userRepository.findByEmail(email).map(User::getId).orElseThrow();
		return new Session(userId,
				objectMapper.readTree(login.getResponse().getContentAsString())
						.get("accessToken").asText(),
				objectMapper.readTree(login.getResponse().getContentAsString())
						.get("refreshToken").asText());
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
}
