package com.soubhagya.policyimpactengine.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;

/**
 * Phase 2S — Testcontainers repository tests: append-only history,
 * newest-first ordering, the sanctioned single terminal transition,
 * write-once payload protection, and schema CHECK constraints.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PolicyFetchAttemptRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyFetchAttemptRepository attemptRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		attemptRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void persistenceRoundTripWithNullablesUnset() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt attempt = attemptRepository.saveAndFlush(
				new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:00:00Z")));

		List<PolicyFetchAttempt> rows = attemptRepository
				.findByPolicy_IdOrderByStartedAtDesc(policy.getId());
		assertThat(rows).hasSize(1);
		PolicyFetchAttempt row = rows.get(0);
		assertThat(row.getId()).isEqualTo(attempt.getId());
		assertThat(row.getPolicy().getId()).isEqualTo(policy.getId());
		assertThat(row.getTrigger()).isEqualTo(PolicyFetchAttemptTrigger.MANUAL);
		assertThat(row.getStatus()).isEqualTo(PolicyFetchAttemptStatus.IN_PROGRESS);
		assertThat(row.getAttemptNumber()).isEqualTo(1);
		assertThat(row.getStartedAt()).isEqualTo(Instant.parse("2026-09-18T10:00:00Z"));
		assertThat(row.getHttpStatus()).isNull();
		assertThat(row.getBytesFetched()).isNull();
		assertThat(row.getDurationMs()).isNull();
		assertThat(row.getErrorMessage()).isNull();
		assertThat(row.getCompletedAt()).isNull();
	}

	@Test
	void historyIsOrderedNewestFirst() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt older = attemptRepository.saveAndFlush(
				new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:00:00Z")));
		// Phase 2U: at most one PENDING/IN_PROGRESS attempt may exist per
		// policy (V11 partial unique index), so history rows after the first
		// must start from a terminal row.
		older.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1L, null, null,
				Instant.parse("2026-09-18T10:00:01Z"));
		attemptRepository.saveAndFlush(older);
		PolicyFetchAttempt newer = attemptRepository.saveAndFlush(
				new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:05:00Z")));

		assertThat(attemptRepository.findByPolicy_IdOrderByStartedAtDesc(policy.getId()))
				.extracting(PolicyFetchAttempt::getId)
				.containsExactly(newer.getId(), older.getId());
	}

	@Test
	void terminalTransitionPersists() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt attempt = attemptRepository.saveAndFlush(
				new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:00:00Z")));
		attempt.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1024L, null, null,
				Instant.parse("2026-09-18T10:00:02Z"));
		attemptRepository.saveAndFlush(attempt);

		PolicyFetchAttempt row = attemptRepository
				.findByPolicy_IdOrderByStartedAtDesc(policy.getId()).get(0);
		assertThat(row.getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(row.getFailureKind()).isNull();
		assertThat(row.getHttpStatus()).isEqualTo(200);
		assertThat(row.getBytesFetched()).isEqualTo(1024L);
		assertThat(row.getDurationMs()).isEqualTo(2000L);
		assertThat(row.getCompletedAt()).isEqualTo(Instant.parse("2026-09-18T10:00:02Z"));
	}

	@Test
	void secondTerminalTransitionIsRejected() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:00:00Z"));
		attempt.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1L, null, null,
				Instant.parse("2026-09-18T10:00:01Z"));

		assertThatThrownBy(() -> attempt.complete(PolicyFetchAttemptStatus.FAILED, 500, 1L,
				"late", PolicyFetchFailureKind.TRANSIENT, Instant.parse("2026-09-18T10:00:02Z")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nonTerminalTargetIsRejected() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:00:00Z"));

		assertThatThrownBy(() -> attempt.complete(PolicyFetchAttemptStatus.IN_PROGRESS, null,
				null, null, null, Instant.parse("2026-09-18T10:00:01Z")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failedTransitionRequiresFailureKind() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:00:00Z"));

		assertThatThrownBy(() -> attempt.complete(PolicyFetchAttemptStatus.FAILED, 500, 1L,
				"down", null, Instant.parse("2026-09-18T10:00:01Z")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("failure kind");
	}

	@Test
	void nonFailedTransitionRejectsFailureKind() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:00:00Z"));

		assertThatThrownBy(() -> attempt.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1L,
				null, PolicyFetchFailureKind.TRANSIENT, Instant.parse("2026-09-18T10:00:01Z")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failedRowPersistsFailureKind() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt transientFailure = attemptRepository.saveAndFlush(
				new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.SCHEDULED, 2,
						Instant.parse("2026-09-18T10:00:00Z")));
		transientFailure.complete(PolicyFetchAttemptStatus.FAILED, null, null, "reset",
				PolicyFetchFailureKind.TRANSIENT, Instant.parse("2026-09-18T10:00:01Z"));
		attemptRepository.saveAndFlush(transientFailure);

		List<PolicyFetchAttempt> rows = attemptRepository
				.findByPolicy_IdOrderByStartedAtDesc(policy.getId());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(rows.get(0).getFailureKind()).isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		assertThat(rows.get(0).getAttemptNumber()).isEqualTo(2);
	}

	@Test
	void invalidFailureKindRejectedByCheckConstraint() {
		Policy policy = registeredPolicy();
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO policy_fetch_attempt (id, policy_id, trigger, status, attempt_number, "
						+ "started_at, completed_at, failure_kind) VALUES (?, ?, 'MANUAL', 'FAILED', 1, "
						+ "now(), now(), 'BOGUS')",
				UUID.randomUUID(), policy.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void constructorGuards() {
		Policy policy = registeredPolicy();
		Instant started = Instant.parse("2026-09-18T10:00:00Z");
		assertThatThrownBy(() -> new PolicyFetchAttempt(null, PolicyFetchAttemptTrigger.MANUAL, 1, started))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttempt(policy, null, 1, started))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 0, started))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, started)
				.complete(null, null, null, null, null, started))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, started)
				.complete(PolicyFetchAttemptStatus.SUCCESS, 99, null, null, null, started))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, started)
				.complete(PolicyFetchAttemptStatus.SUCCESS, null, -1L, null, null, started))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, started)
				.complete(PolicyFetchAttemptStatus.SUCCESS, null, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidStatusRejectedByCheckConstraint() {
		Policy policy = registeredPolicy();
		assertThatThrownBy(() -> insertRawAttempt(policy.getId(), "BOGUS", "IN_PROGRESS"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void terminalStatusWithoutCompletedAtRejectedByCheckConstraint() {
		Policy policy = registeredPolicy();
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO policy_fetch_attempt (id, policy_id, trigger, status, attempt_number, started_at) "
						+ "VALUES (?, ?, 'MANUAL', 'SUCCESS', 1, now())",
				UUID.randomUUID(), policy.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void negativeBytesRejectedByCheckConstraint() {
		Policy policy = registeredPolicy();
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO policy_fetch_attempt (id, policy_id, trigger, status, bytes_fetched, "
						+ "attempt_number, started_at) VALUES (?, ?, 'MANUAL', 'IN_PROGRESS', -1, 1, now())",
				UUID.randomUUID(), policy.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unknownPolicyRejectedByForeignKey() {
		assertThatThrownBy(() -> insertRawAttempt(UUID.randomUUID(), "MANUAL", "IN_PROGRESS"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private int insertRawAttempt(UUID policyId, String trigger, String status) {
		return jdbcTemplate.update(
				"INSERT INTO policy_fetch_attempt (id, policy_id, trigger, status, attempt_number, started_at) "
						+ "VALUES (?, ?, ?, ?, 1, now())",
				UUID.randomUUID(), policyId, trigger, status);
	}

	@Test
	void writeOnceAndLifecycleColumnConventions() {
		List<String> setters = Arrays.stream(PolicyFetchAttempt.class.getMethods())
				.map(Method::getName).filter(n -> n.startsWith("set")).toList();
		assertThat(setters).isEmpty();
		// Lifecycle columns are the only ones allowed to change after insert
		// (single sanctioned terminal transition); everything else is
		// strictly write-once.
		List<String> lifecycleColumns = List.of("status", "httpStatus", "bytesFetched",
				"durationMs", "errorMessage", "failureKind", "completedAt");
		Arrays.stream(PolicyFetchAttempt.class.getDeclaredFields())
				.filter(f -> f.isAnnotationPresent(Column.class))
				.forEach(f -> {
					boolean expected = lifecycleColumns.contains(f.getName());
					assertThat(f.getAnnotation(Column.class).updatable())
							.as("Column %s", f.getName()).isEqualTo(expected);
				});
		Arrays.stream(PolicyFetchAttempt.class.getDeclaredFields())
				.filter(f -> f.isAnnotationPresent(JoinColumn.class))
				.forEach(f -> assertThat(f.getAnnotation(JoinColumn.class).updatable())
						.as("Join %s", f.getName()).isFalse());
	}

	private Policy registeredPolicy() {
		return policyRepository.saveAndFlush(
				new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
	}
}
