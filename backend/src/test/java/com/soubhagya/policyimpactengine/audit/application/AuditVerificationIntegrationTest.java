package com.soubhagya.policyimpactengine.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEvent;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.audit.domain.AuditVerificationResult;
import com.soubhagya.policyimpactengine.audit.domain.AuditVerificationResult.FailureReason;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 11B — Testcontainers integration tests for
 * {@link AuditVerificationService} against real PostgreSQL.
 *
 * <p>Valid chains are built through {@link AuditService}; corrupt
 * states that JPA validation would reject are injected with
 * {@link JdbcTemplate} so entity validation stays strict. Nothing
 * here emits audit events beyond the rows under verification (11C
 * scope) and no REST surface is involved (11D scope).
 */
@SpringBootTest
@Testcontainers
class AuditVerificationIntegrationTest {

	private static final Instant FIRST = Instant.parse("2026-09-18T10:00:00Z");
	private static final UUID VECTOR_ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String VECTOR_HASH =
			"6c3fd51f3d347c9d818b4cc35d6e2dafd83a11b849f62e1bee3c31f5fa3fcec4";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private UserRepository userRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private AuditService auditService;
	@Autowired private AuditVerificationService verificationService;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void emptyDatabaseIsValidWithZeroCount() {
		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isZero();
		assertThat(result.getFailureReason()).isEmpty();
	}

	@Test
	void serviceAppendedGenesisVerifies() {
		User user = userRepository.saveAndFlush(new User());
		auditService.append(user.getId(), AuditEventType.AUTH_USER_REGISTERED,
				"USER", user.getId(), null, FIRST);

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(1);
	}

	@Test
	void serviceAppendedChainVerifies() {
		User user = userRepository.saveAndFlush(new User());
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("conceptCode", "LOCATION");
		fields.put("newSensitivity", 5);
		auditService.append(user.getId(), AuditEventType.AUTH_USER_REGISTERED,
				"USER", user.getId(), null, FIRST);
		auditService.append(user.getId(), AuditEventType.PRIVACY_PREFERENCE_UPSERTED,
				"USER", user.getId(), AuditMetadata.of(fields), FIRST.plusSeconds(60));
		auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED,
				null, null, null, FIRST.plusSeconds(120));

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(3);
	}

	@Test
	void exactAdrGoldenVectorRowVerifies() {
		jdbcTemplate.update("INSERT INTO app_user (id, created_at, updated_at) "
				+ "VALUES (?, ?, ?)", VECTOR_ACTOR, Timestamp.from(FIRST),
				Timestamp.from(FIRST));
		jdbcTemplate.update("INSERT INTO audit_event (id, occurred_at, actor_user_id, "
				+ "event_type, resource_type, resource_id, metadata, prev_hash, event_hash) "
				+ "VALUES (?, ?, ?, 'AUTH_USER_REGISTERED', 'USER', ?, '{}', NULL, ?)",
				UUID.randomUUID(), Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
				VECTOR_ACTOR, VECTOR_ACTOR, VECTOR_HASH);

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(1);
	}

	@Test
	void jdbcMetadataCorruptionIsDetected() {
		User user = userRepository.saveAndFlush(new User());
		AuditEvent genesis = auditService.append(user.getId(),
				AuditEventType.AUTH_USER_REGISTERED, "USER", user.getId(), null, FIRST);
		AuditEvent second = auditService.append(user.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				FIRST.plusSeconds(60));

		jdbcTemplate.update("UPDATE audit_event SET metadata = '{\"x\":1}' WHERE id = ?",
				second.getId());

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.HASH_MISMATCH);
		assertThat(result.getFailedEventId()).contains(second.getId());
		assertThat(result.getFailedPosition()).contains(1);
		assertThat(result.getVerifiedCount()).isEqualTo(1);
		assertThat(genesis.getId()).isNotNull();
	}

	@Test
	void jdbcEventHashCorruptionIsDetected() {
		User user = userRepository.saveAndFlush(new User());
		auditService.append(user.getId(), AuditEventType.AUTH_USER_REGISTERED,
				"USER", user.getId(), null, FIRST);
		AuditEvent second = auditService.append(user.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				FIRST.plusSeconds(60));

		jdbcTemplate.update("UPDATE audit_event SET event_hash = ? WHERE id = ?",
				"e".repeat(64), second.getId());

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.HASH_MISMATCH);
		assertThat(result.getFailedEventId()).contains(second.getId());
	}

	@Test
	void jdbcPrevHashCorruptionToRandomHashIsMissingPredecessor() {
		User user = userRepository.saveAndFlush(new User());
		auditService.append(user.getId(), AuditEventType.AUTH_USER_REGISTERED,
				"USER", user.getId(), null, FIRST);
		auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED,
				null, null, null, FIRST.plusSeconds(60));
		AuditEvent third = auditService.append(user.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				FIRST.plusSeconds(120));

		jdbcTemplate.update("UPDATE audit_event SET prev_hash = ? WHERE id = ?",
				"f".repeat(64), third.getId());

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.MISSING_PREDECESSOR);
		assertThat(result.getFailedEventId()).contains(third.getId());
		assertThat(result.getVerifiedCount()).isEqualTo(2);
	}

	@Test
	void databaseRejectsSharedPredecessorAndChainStaysValid() {
		User user = userRepository.saveAndFlush(new User());
		AuditEvent genesis = auditService.append(user.getId(),
				AuditEventType.AUTH_USER_REGISTERED, "USER", user.getId(), null, FIRST);
		auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED,
				null, null, null, FIRST.plusSeconds(60));
		AuditEvent third = auditService.append(user.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				FIRST.plusSeconds(120));

		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE audit_event SET prev_hash = ? WHERE id = ?",
				genesis.getEventHash(), third.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(auditEventRepository.count()).isEqualTo(3);
		AuditVerificationResult result = verificationService.verify();
		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(3);
	}

	@Test
	void deletedPredecessorIsDetected() {
		User user = userRepository.saveAndFlush(new User());
		auditService.append(user.getId(), AuditEventType.AUTH_USER_REGISTERED,
				"USER", user.getId(), null, FIRST);
		AuditEvent second = auditService.append(user.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				FIRST.plusSeconds(60));
		AuditEvent third = auditService.append(user.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				FIRST.plusSeconds(120));

		jdbcTemplate.update("DELETE FROM audit_event WHERE id = ?", second.getId());

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.MISSING_PREDECESSOR);
		assertThat(result.getFailedEventId()).contains(third.getId());
		assertThat(result.getVerifiedCount()).isEqualTo(1);
	}

	@Test
	void jdbcInjectedCycleIsUnreachable() {
		User user = userRepository.saveAndFlush(new User());
		auditService.append(user.getId(), AuditEventType.AUTH_USER_REGISTERED,
				"USER", user.getId(), null, FIRST);
		String hashB = "b".repeat(64);
		String hashC = "c".repeat(64);
		jdbcTemplate.update("INSERT INTO audit_event (id, occurred_at, actor_user_id, "
				+ "event_type, prev_hash, event_hash) VALUES (?, ?, ?, "
				+ "'AUTH_LOGIN_SUCCEEDED', ?, ?)", UUID.randomUUID(),
				Timestamp.from(FIRST.plusSeconds(1)), user.getId(), hashC, hashB);
		UUID memberC = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO audit_event (id, occurred_at, actor_user_id, "
				+ "event_type, prev_hash, event_hash) VALUES (?, ?, ?, "
				+ "'AUTH_LOGIN_SUCCEEDED', ?, ?)", memberC,
				Timestamp.from(FIRST.plusSeconds(2)), user.getId(), hashB, hashC);

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.UNREACHABLE_ROW);
		assertThat(result.getVerifiedCount()).isEqualTo(1);
	}

	@Test
	void databaseRejectsTwinGenesisAndChainStaysValid() {
		User user = userRepository.saveAndFlush(new User());
		auditService.append(user.getId(), AuditEventType.AUTH_USER_REGISTERED,
				"USER", user.getId(), null, FIRST);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO audit_event (id, occurred_at, event_type, event_hash) "
						+ "VALUES (?, ?, 'AUTH_USER_REGISTERED', ?)", UUID.randomUUID(),
				Timestamp.from(FIRST.plusSeconds(1)), "d".repeat(64)))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(auditEventRepository.count()).isEqualTo(1);
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	@Test
	void nullActorChainVerifies() {
		auditService.append(null, AuditEventType.AUTH_LOGIN_SUCCEEDED,
				null, null, null, FIRST);
		auditService.append(null, AuditEventType.AUTH_LOGIN_SUCCEEDED,
				null, null, null, FIRST.plusSeconds(60));

		AuditVerificationResult result = verificationService.verify();

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(2);
	}

	@Test
	void verificationDoesNotMutatePersistedRows() {
		User user = userRepository.saveAndFlush(new User());
		auditService.append(user.getId(), AuditEventType.AUTH_USER_REGISTERED,
				"USER", user.getId(), null, FIRST);
		auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED,
				null, null, null, FIRST.plusSeconds(60));

		List<Map<String, Object>> before = dumpRows();
		AuditVerificationResult result = verificationService.verify();
		List<Map<String, Object>> after = dumpRows();

		assertThat(result.isValid()).isTrue();
		assertThat(after).isEqualTo(before);
	}

	private List<Map<String, Object>> dumpRows() {
		return jdbcTemplate.queryForList("SELECT id, occurred_at, actor_user_id, "
				+ "event_type, resource_type, resource_id, metadata, prev_hash, event_hash "
				+ "FROM audit_event ORDER BY id");
	}
}
