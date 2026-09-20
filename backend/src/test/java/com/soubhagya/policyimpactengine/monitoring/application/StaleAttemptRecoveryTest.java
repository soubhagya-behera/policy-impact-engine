package com.soubhagya.policyimpactengine.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;

/**
 * Phase 2U.2 — deterministic unit tests for stale {@code IN_PROGRESS}
 * recovery.
 *
 * <p>No Spring context, no database, no network. A fixed clock pins the
 * recovery time so the stale cutoff ({@code now - timeout}) and every
 * timestamp assertion is exact; the transaction manager is a Mockito mock
 * following the established {@code TransactionTemplate} unit-test pattern.
 * Retry math uses a real {@link RetryPolicy} with a seeded jitter source
 * plus an identically seeded control instance.
 */
@ExtendWith(MockitoExtension.class)
class StaleAttemptRecoveryTest {

	private static final Duration TIMEOUT = Duration.ofMinutes(30);

	@Mock
	private PolicyFetchAttemptRepository attemptRepository;

	@Mock
	private PolicyRepository policyRepository;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private ManualClock clock;
	private RetryPolicy retry;
	private RetryPolicy control;
	private StaleAttemptRecovery recovery;

	@BeforeEach
	void setUp() {
		clock = new ManualClock(Instant.parse("2026-09-18T10:00:00Z"));
		retry = new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(11));
		control = new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(11));
		recovery = new StaleAttemptRecovery(attemptRepository, policyRepository,
				transactionManager, clock, retry, TIMEOUT, 100);
	}

	private void givenTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	@Test
	void exactlyAtThresholdCountsAsStale() {
		givenTransaction();
		Policy policy = registeredPolicy();
		PolicyFetchAttempt stale = inProgress(policy, clock.instant().minus(TIMEOUT), 1);
		givenCandidates(stale);
		givenSnapshot(stale);
		givenRecoveryWins();
		givenPolicy(policy);

		int recovered = recovery.recoverStaleAttempts(clock.instant());

		assertThat(recovered).isEqualTo(1);
		verify(attemptRepository).recoverStaleAttempt(eq(stale.getId()),
				eq(PolicyFetchAttemptStatus.IN_PROGRESS), eq(clock.instant().minus(TIMEOUT)),
				eq(PolicyFetchAttemptStatus.FAILED), eq(PolicyFetchFailureKind.TRANSIENT),
				any(), eq(clock.instant()), anyLong());
	}

	@Test
	void oneMillisecondBeforeThresholdIsNotStale() {
		Policy policy = registeredPolicy();
		Instant now = clock.instant();
		// started 1ms after the cutoff: age is timeout minus 1ms.
		PolicyFetchAttempt fresh = inProgress(policy, now.minus(TIMEOUT).plusMillis(1), 1);
		givenCandidates(fresh);
		givenSnapshot(fresh);

		int recovered = recovery.recoverStaleAttempts(now);

		assertThat(recovered).isEqualTo(0);
		verify(attemptRepository, never()).recoverStaleAttempt(any(), any(), any(),
				any(), any(), any(), any(), anyLong());
		verify(policyRepository, never()).save(any());
	}

	@Test
	void selectionUsesStartedAtCutoffOldestFirstWithBatchPage() {
		givenCandidates();

		recovery.recoverStaleAttempts(clock.instant());

		verify(attemptRepository).findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
				eq(PolicyFetchAttemptStatus.IN_PROGRESS),
				eq(clock.instant().minus(TIMEOUT)),
				eq(PageRequest.of(0, 100)));
	}

	@Test
	void pendingRowsAreNeverRecovered() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt pending = withId(PolicyFetchAttempt.pending(policy,
				PolicyFetchAttemptTrigger.SCHEDULED, 1, clock.instant().minus(Duration.ofHours(2))));
		// Even if a PENDING row leaked into the candidate list, the
		// recovery path must not transition it.
		when(attemptRepository.findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
				eq(PolicyFetchAttemptStatus.IN_PROGRESS), any(), any()))
				.thenReturn(List.of(pending));
		when(attemptRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

		int recovered = recovery.recoverStaleAttempts(clock.instant());

		assertThat(recovered).isEqualTo(0);
		verify(attemptRepository, never()).recoverStaleAttempt(any(), any(), any(),
				any(), any(), any(), any(), anyLong());
		verify(policyRepository, never()).save(any());
	}

	@Test
	void terminalRowsAreNeverRecovered() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt terminal = inProgress(policy,
				clock.instant().minus(Duration.ofHours(2)), 1);
		terminal.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 10L, null, null,
				clock.instant().minus(Duration.ofHours(1)));
		when(attemptRepository.findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
				eq(PolicyFetchAttemptStatus.IN_PROGRESS), any(), any()))
				.thenReturn(List.of(terminal));
		when(attemptRepository.findById(terminal.getId())).thenReturn(Optional.of(terminal));

		int recovered = recovery.recoverStaleAttempts(clock.instant());

		assertThat(recovered).isEqualTo(0);
		verify(attemptRepository, never()).recoverStaleAttempt(any(), any(), any(),
				any(), any(), any(), any(), anyLong());
		verify(policyRepository, never()).save(any());
	}

	@Test
	void staleRowBecomesFailedTransientWithStaleMessageCompletionAndDuration() {
		givenTransaction();
		Policy policy = registeredPolicy();
		Instant started = clock.instant().minus(Duration.ofHours(1));
		PolicyFetchAttempt stale = inProgress(policy, started, 2);
		givenCandidates(stale);
		givenSnapshot(stale);
		givenRecoveryWins();
		givenPolicy(policy);

		int recovered = recovery.recoverStaleAttempts(clock.instant());

		assertThat(recovered).isEqualTo(1);
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Long> duration = ArgumentCaptor.forClass(Long.class);
		verify(attemptRepository).recoverStaleAttempt(eq(stale.getId()),
				eq(PolicyFetchAttemptStatus.IN_PROGRESS), eq(clock.instant().minus(TIMEOUT)),
				eq(PolicyFetchAttemptStatus.FAILED), eq(PolicyFetchFailureKind.TRANSIENT),
				message.capture(), eq(clock.instant()), duration.capture());
		assertThat(message.getValue()).startsWith("Stale IN_PROGRESS");
		assertThat(duration.getValue()).isEqualTo(Duration.between(started, clock.instant()).toMillis());
		assertThat(duration.getValue()).isGreaterThanOrEqualTo(0L);
	}

	@Test
	void rescheduleReusesRetryPolicyWithCompletedAtTransientAndOriginalNumber() {
		givenTransaction();
		Policy policy = registeredPolicy();
		PolicyFetchAttempt stale = inProgress(policy,
				clock.instant().minus(Duration.ofHours(1)), 3);
		givenCandidates(stale);
		givenSnapshot(stale);
		givenRecoveryWins();
		givenPolicy(policy);
		Instant expectedNextCheck = control.nextCheckAt(clock.instant(),
				PolicyFetchFailureKind.TRANSIENT, 3);

		recovery.recoverStaleAttempts(clock.instant());

		ArgumentCaptor<Policy> saved = ArgumentCaptor.forClass(Policy.class);
		verify(policyRepository).save(saved.capture());
		assertThat(saved.getValue().getNextCheckAt()).isEqualTo(expectedNextCheck);
	}

	@Test
	void lostRacePerformsNoReschedule() {
		givenTransaction();
		Policy policy = registeredPolicy();
		PolicyFetchAttempt stale = inProgress(policy,
				clock.instant().minus(Duration.ofHours(1)), 1);
		givenCandidates(stale);
		givenSnapshot(stale);
		when(attemptRepository.recoverStaleAttempt(any(), any(), any(), any(), any(),
				any(), any(), anyLong())).thenReturn(0);

		int recovered = recovery.recoverStaleAttempts(clock.instant());

		assertThat(recovered).isEqualTo(0);
		verify(policyRepository, never()).findById(any());
		verify(policyRepository, never()).save(any());
	}

	@Test
	void missingRowIsSkippedWithoutReschedule() {
		Policy policy = registeredPolicy();
		PolicyFetchAttempt stale = inProgress(policy,
				clock.instant().minus(Duration.ofHours(1)), 1);
		givenCandidates(stale);
		when(attemptRepository.findById(stale.getId())).thenReturn(Optional.empty());

		int recovered = recovery.recoverStaleAttempts(clock.instant());

		assertThat(recovered).isEqualTo(0);
		verify(attemptRepository, never()).recoverStaleAttempt(any(), any(), any(),
				any(), any(), any(), any(), anyLong());
		verify(policyRepository, never()).save(any());
	}

	@Test
	void poisonRowDoesNotAbortRemainingCandidates() {
		givenTransaction();
		Policy firstPolicy = registeredPolicy();
		Policy secondPolicy = registeredPolicy();
		PolicyFetchAttempt poison = inProgress(firstPolicy,
				clock.instant().minus(Duration.ofHours(2)), 1);
		PolicyFetchAttempt healthy = inProgress(secondPolicy,
				clock.instant().minus(Duration.ofHours(1)), 1);
		givenCandidates(poison, healthy);
		givenSnapshot(poison);
		givenSnapshot(healthy);
		when(attemptRepository.recoverStaleAttempt(eq(poison.getId()), any(), any(), any(),
				any(), any(), any(), anyLong()))
				.thenThrow(new IllegalStateException("poisoned row"));
		when(attemptRepository.recoverStaleAttempt(eq(healthy.getId()), any(), any(), any(),
				any(), any(), any(), anyLong())).thenReturn(1);
		givenPolicy(secondPolicy);

		int recovered = recovery.recoverStaleAttempts(clock.instant());

		assertThat(recovered).isEqualTo(1);
		verify(policyRepository).save(secondPolicy);
	}

	@Test
	void recoveryTimeMustNotBeNull() {
		assertThatThrownBy(() -> recovery.recoverStaleAttempts(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void constructorRejectsInvalidConfiguration() {
		PolicyFetchAttemptRepository attempts = mock(PolicyFetchAttemptRepository.class);
		PolicyRepository policies = mock(PolicyRepository.class);
		PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
		RetryPolicy retryPolicy = new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(1));
		assertThatThrownBy(() -> new StaleAttemptRecovery(attempts, policies, tx, clock,
				retryPolicy, Duration.ZERO, 100))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StaleAttemptRecovery(attempts, policies, tx, clock,
				retryPolicy, TIMEOUT, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private void givenCandidates(PolicyFetchAttempt... candidates) {
		when(attemptRepository.findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
				eq(PolicyFetchAttemptStatus.IN_PROGRESS), any(), any()))
				.thenReturn(List.of(candidates));
	}

	private void givenSnapshot(PolicyFetchAttempt snapshot) {
		when(attemptRepository.findById(snapshot.getId()))
				.thenReturn(Optional.of(snapshot));
	}

	private void givenRecoveryWins() {
		when(attemptRepository.recoverStaleAttempt(any(), any(), any(), any(), any(),
				any(), any(), anyLong())).thenReturn(1);
	}

	private void givenPolicy(Policy policy) {
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
	}

	private PolicyFetchAttempt inProgress(Policy policy, Instant startedAt, int attemptNumber) {
		return withId(new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.SCHEDULED,
				attemptNumber, startedAt));
	}

	/**
	 * Test-only identifier assignment: unit-test attempts are never
	 * persisted, so {@code @GeneratedValue} never fires. Production rows
	 * always carry database-assigned ids.
	 */
	private static PolicyFetchAttempt withId(PolicyFetchAttempt attempt) {
		try {
			Field id = PolicyFetchAttempt.class.getDeclaredField("id");
			id.setAccessible(true);
			id.set(attempt, UUID.randomUUID());
			return attempt;
		}
		catch (ReflectiveOperationException reflectionFailure) {
			throw new IllegalStateException("Cannot assign attempt id", reflectionFailure);
		}
	}

	private Policy registeredPolicy() {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setId(UUID.randomUUID());
		return policy;
	}

	/** Deterministic manually advanced clock for exact cutoff assertions. */
	private static final class ManualClock extends Clock {

		private Instant now;

		ManualClock(Instant now) {
			this.now = now;
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
