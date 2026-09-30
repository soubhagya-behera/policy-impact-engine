package com.soubhagya.policyimpactengine.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.application.AuditAppendException;
import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 15-B/2 — audit-failure isolation for refresh-reuse auditing
 * (see DECISIONS.md ADR-032 §9).
 *
 * <p>Proves the critical transaction rule: the family revocation is
 * committed before the reuse audit append, so a failing append can
 * neither restore revoked rows nor change the outward response — the
 * caller still receives the byte-identical uniform 401 with no oracle.
 * The audit service is mocked to fail only the reuse event; rotation
 * and revocation stay real against Testcontainers PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthRefreshReuseAuditFailureIntegrationTest {

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

	@MockitoBean
	private AuditService auditService;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void auditFailureKeeps401AndCommittedRevocation() throws Exception {
		doThrow(new AuditAppendException("forced audit failure", null))
				.when(auditService).append(any(), eq(AuditEventType.AUTH_REFRESH_REUSE_DETECTED),
						any(), any(), any(), any());

		String first = registerAndLogin("reuse-audit-down@example.com");
		UUID ownerId = userRepository.findByEmail("reuse-audit-down@example.com")
				.map(User::getId).orElseThrow();
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

		verify(auditService).append(eq(ownerId),
				eq(AuditEventType.AUTH_REFRESH_REUSE_DETECTED),
				eq("USER"), eq(ownerId), any(), eq(null));
		assertThat(liveCount(ownerId)).isZero();
		assertThat(auditCount("AUTH_REFRESH_REUSE_DETECTED")).isZero();
	}

	@Test
	void nonReuseFailuresAttemptNoReuseAudit() throws Exception {
		registerAndLogin("reuse-audit-quiet@example.com");

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));

		verify(auditService, never()).append(any(),
				eq(AuditEventType.AUTH_REFRESH_REUSE_DETECTED),
				any(), any(), any(), any());
	}

	private void rotateExpectOk(String refreshToken) throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refreshToken)))
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
				.get("refreshToken").asText();
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
