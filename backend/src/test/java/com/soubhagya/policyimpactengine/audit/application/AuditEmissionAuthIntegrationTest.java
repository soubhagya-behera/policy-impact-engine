package com.soubhagya.policyimpactengine.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.user.AuthLoginService;
import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.DuplicateEmailException;
import com.soubhagya.policyimpactengine.user.InvalidCredentialsException;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;
import com.soubhagya.policyimpactengine.user.web.AuthController;
import com.soubhagya.policyimpactengine.user.web.dto.LoginRequest;
import com.soubhagya.policyimpactengine.user.web.dto.RegisterRequest;

/**
 * Phase 11C — Testcontainers proof that authentication flows emit
 * exactly the frozen audit events: one {@code AUTH_USER_REGISTERED}
 * per created user and one {@code AUTH_LOGIN_SUCCEEDED} per verified
 * login, both post-commit through the controllers. Failures stay
 * silent, credentials never enter audit data, and an audit failure
 * never rolls back the committed user.
 */
@SpringBootTest
@Testcontainers
class AuditEmissionAuthIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private AuthRegistrationService registrationService;
	@Autowired private AuthLoginService loginService;
	@Autowired private AuthController authController;
	@Autowired private UserRepository userRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void successfulRegistrationEmitsOneEvent() {
		UUID id = authController
				.register(new RegisterRequest("user@example.com", "correct-horse-1"))
				.getBody().id();

		List<Map<String, Object>> rows = rowsByType("AUTH_USER_REGISTERED");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("actor_user_id")).isEqualTo(id);
		assertThat(rows.get(0).get("resource_type")).isEqualTo("USER");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(id);
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");
	}

	@Test
	void duplicateRegistrationEmitsNothingNew() {
		authController.register(new RegisterRequest("user@example.com", "correct-horse-1"));

		assertThatThrownBy(() -> authController
				.register(new RegisterRequest("user@example.com", "correct-horse-1")))
				.isInstanceOf(DuplicateEmailException.class);

		assertThat(countByType("AUTH_USER_REGISTERED")).isEqualTo(1);
		assertThat(auditEventRepository.count()).isEqualTo(1);
	}

	@Test
	void invalidRegistrationEmitsNothing() {
		assertThatThrownBy(() -> registrationService.register("user@example.com", "short"))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(auditEventRepository.count()).isZero();
		assertThat(userRepository.count()).isZero();
	}

	@Test
	void successfulLoginEmitsOneEvent() {
		UUID id = authController
				.register(new RegisterRequest("user@example.com", "correct-horse-1"))
				.getBody().id();
		authController.login(new LoginRequest("user@example.com", "correct-horse-1"));

		List<Map<String, Object>> rows = rowsByType("AUTH_LOGIN_SUCCEEDED");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("actor_user_id")).isEqualTo(id);
		assertThat(rows.get(0).get("resource_type")).isEqualTo("USER");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(id);
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");
		assertThat(countByType("AUTH_USER_REGISTERED")).isEqualTo(1);
	}

	@Test
	void failedLoginsEmitNothing() {
		authController.register(new RegisterRequest("user@example.com", "correct-horse-1"));

		assertThatThrownBy(() -> authController
				.login(new LoginRequest("user@example.com", "wrong-password-2")))
				.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> authController
				.login(new LoginRequest("absent@example.com", "correct-horse-1")))
				.isInstanceOf(InvalidCredentialsException.class);

		assertThat(countByType("AUTH_LOGIN_SUCCEEDED")).isZero();
		assertThat(countByType("AUTH_USER_REGISTERED")).isEqualTo(1);
	}

	@Test
	void accessTokenNeverAppearsInAuditData() {
		authController.register(new RegisterRequest("user@example.com", "correct-horse-1"));
		String token = authController
				.login(new LoginRequest("user@example.com", "correct-horse-1"))
				.getBody().accessToken();

		assertThat(token).isNotBlank();
		List<String> metadata = jdbcTemplate.queryForList(
				"SELECT metadata FROM audit_event", String.class);
		assertThat(metadata).isNotEmpty();
		assertThat(metadata).noneMatch(
				value -> value != null && value.contains(token));
	}

	@Test
	void successfulLoginPersistsOneUsableRefreshRow() {
		UUID id = authController
				.register(new RegisterRequest("user@example.com", "correct-horse-1"))
				.getBody().id();
		String refreshToken = authController
				.login(new LoginRequest("user@example.com", "correct-horse-1"))
				.getBody().refreshToken();

		assertThat(refreshToken).isNotBlank();
		assertThat(refreshTokenRepository.count()).isEqualTo(1);
		assertThat(refreshTokenRepository.findAll().get(0).getTokenHash())
				.isEqualTo(sha256Hex(refreshToken));
		assertThat(refreshTokenRepository.findAll().get(0).getUser().getId())
				.isEqualTo(id);
	}

	@Test
	void refreshTokenNeverAppearsInAuditData() {
		authController.register(new RegisterRequest("user@example.com", "correct-horse-1"));
		String refreshToken = authController
				.login(new LoginRequest("user@example.com", "correct-horse-1"))
				.getBody().refreshToken();

		assertThat(refreshToken).isNotBlank();
		List<String> metadata = jdbcTemplate.queryForList(
				"SELECT metadata FROM audit_event", String.class);
		assertThat(metadata).isNotEmpty();
		assertThat(metadata).noneMatch(
				value -> value != null && value.contains(refreshToken));
	}

	@Test
	void auditFailureDoesNotRollBackRegistration() {
		AuditService failingAudit = mock(AuditService.class);
		doThrow(new AuditAppendException("forced audit failure", null))
				.when(failingAudit).append(any(), any(), any(), any(), any(), any());
		AuthController failingController = new AuthController(registrationService,
				loginService, failingAudit);

		assertThatThrownBy(() -> failingController
				.register(new RegisterRequest("user@example.com", "correct-horse-1")))
				.isInstanceOf(AuditAppendException.class);

		assertThat(userRepository.findByEmail("user@example.com")).isPresent();
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void auditFailureDoesNotRollBackLoginRefreshRow() {
		authController.register(new RegisterRequest("user@example.com", "correct-horse-1"));
		AuditService failingAudit = mock(AuditService.class);
		doThrow(new AuditAppendException("forced audit failure", null))
				.when(failingAudit).append(any(), any(), any(), any(), any(), any());
		AuthController failingController = new AuthController(registrationService,
				loginService, failingAudit);

		assertThatThrownBy(() -> failingController
				.login(new LoginRequest("user@example.com", "correct-horse-1")))
				.isInstanceOf(AuditAppendException.class);

		assertThat(refreshTokenRepository.count()).isEqualTo(1);
		assertThat(countByType("AUTH_LOGIN_SUCCEEDED")).isZero();
	}

	@Test
	void emittedAuthMetadataContainsNoForbiddenContent() {
		authController.register(new RegisterRequest("user@example.com", "correct-horse-1"));
		authController.login(new LoginRequest("user@example.com", "correct-horse-1"));

		List<String> metadata = jdbcTemplate.queryForList(
				"SELECT metadata FROM audit_event WHERE event_type IN "
						+ "('AUTH_USER_REGISTERED','AUTH_LOGIN_SUCCEEDED')",
				String.class);
		assertThat(metadata).hasSize(2);
		assertThat(metadata).allSatisfy(
				value -> assertThat(value).isEqualTo("{}"));
		assertThat(authController.register(
				new RegisterRequest("second@example.com", "correct-horse-1"))
				.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		List<String> allMetadata = jdbcTemplate.queryForList(
				"SELECT metadata FROM audit_event", String.class);
		assertThat(allMetadata).noneMatch(value -> value != null
				&& value.toLowerCase().matches(
						"(?s).*(password|token|authorization|secret|email|url|content).*"));
	}

	private int countByType(String eventType) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ?",
				Integer.class, eventType);
		return count == null ? 0 : count;
	}

	private List<Map<String, Object>> rowsByType(String eventType) {
		return jdbcTemplate.queryForList("SELECT actor_user_id, resource_type, resource_id, "
				+ "metadata FROM audit_event WHERE event_type = ? ORDER BY occurred_at, id",
				eventType);
	}

	/**
	 * Independent JDK SHA-256 oracle for asserting the persisted
	 * digest matches the returned raw token (the service helper is
	 * package-private to the user module).
	 */
	private static String sha256Hex(String rawToken) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
					.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(64);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (java.security.NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
	}
}
