package com.soubhagya.policyimpactengine.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
import com.soubhagya.policyimpactengine.policy.domain.Policy;

/**
 * Phase 2S/2U.1 — deterministic unit tests for the attempt lifecycle and
 * claim service.
 *
 * <p>No Spring context, no database, no network. A manually advanced clock
 * makes durations exact; the transaction manager is a Mockito mock following
 * the established {@code TransactionTemplate} unit-test pattern. Retry
 * chain numbering uses a real {@link RetryPolicy} with fixed bounds.
 */
@ExtendWith(MockitoExtension.class)
class PolicyFetchAttemptServiceTest {

	@Mock
	private PolicyFetchAttemptRepository attemptRepository;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private ManualClock clock;
	private PolicyFetchAttemptService service;

	@BeforeEach
	void setUp() {
		clock = new ManualClock(Instant.parse("2026-09-18T10:00:00Z"));
		RetryPolicy retryPolicy = new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(0));
		service = new PolicyFetchAttemptService(attemptRepository, transactionManager, clock, retryPolicy);
	}

	private void givenTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	private void givenNoHistory() {
		when(attemptRepository.findFirstByPolicy_IdOrderByStartedAtDesc(any()))
				.thenReturn(Optional.empty());
	}

	private void givenLatest(PolicyFetchAttempt latest) {
		when(attemptRepository.findFirstByPolicy_IdOrderByStartedAtDesc(any()))
				.thenReturn(Optional.of(latest));
	}

	@Test
	void beginAttemptClaimsPendingCandidateAsInProgressWithAttemptNumberOne() {
		givenTransaction();
		givenNoHistory();
		Policy policy = registeredPolicy();
		when(attemptRepository.saveAndFlush(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptRepository.claimPendingAttempt(any(), eq(PolicyFetchAttemptStatus.PENDING),
				eq(PolicyFetchAttemptStatus.IN_PROGRESS), eq(clock.instant())))
				.thenReturn(1);
		PolicyFetchAttempt claimed = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, clock.instant());
		when(attemptRepository.findById(any())).thenReturn(Optional.of(claimed));

		PolicyFetchAttempt attempt = service.beginAttempt(policy, PolicyFetchAttemptTrigger.MANUAL);

		ArgumentCaptor<PolicyFetchAttempt> captor = ArgumentCaptor.forClass(PolicyFetchAttempt.class);
		verify(attemptRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getStatus()).isEqualTo(PolicyFetchAttemptStatus.PENDING);
		assertThat(captor.getValue().getAttemptNumber()).isEqualTo(1);
		assertThat(captor.getValue().getStartedAt()).isEqualTo(clock.instant());
		assertThat(captor.getValue().getTrigger()).isEqualTo(PolicyFetchAttemptTrigger.MANUAL);
		assertThat(captor.getValue().getPolicy().getId()).isEqualTo(policy.getId());
		assertThat(captor.getValue().getCompletedAt()).isNull();
		verify(attemptRepository).claimPendingAttempt(any(),
				eq(PolicyFetchAttemptStatus.PENDING),
				eq(PolicyFetchAttemptStatus.IN_PROGRESS),
				eq(clock.instant()));
		assertThat(attempt).isSameAs(claimed);
	}

	@Test
	void beginAttemptCollisionBecomesClaimRejected() {
		givenTransaction();
		givenNoHistory();
		Policy policy = registeredPolicy();
		when(attemptRepository.saveAndFlush(any(PolicyFetchAttempt.class)))
				.thenThrow(new DataIntegrityViolationException("uq_policy_fetch_attempt_inflight"));

		assertThatThrownBy(() -> service.beginAttempt(policy, PolicyFetchAttemptTrigger.MANUAL))
				.isInstanceOf(PolicyFetchClaimRejectedException.class)
				.hasMessageContaining(policy.getId().toString());

		verify(attemptRepository, never()).claimPendingAttempt(any(), any(), any(), any());
	}

	@Test
	void beginAttemptLostClaimIsRejected() {
		givenTransaction();
		givenNoHistory();
		Policy policy = registeredPolicy();
		when(attemptRepository.saveAndFlush(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptRepository.claimPendingAttempt(any(), any(), any(), any()))
				.thenReturn(0);

		assertThatThrownBy(() -> service.beginAttempt(policy, PolicyFetchAttemptTrigger.SCHEDULED))
				.isInstanceOf(PolicyFetchClaimRejectedException.class)
				.hasMessageContaining(policy.getId().toString());
	}

	@Test
	void beginAttemptContinuesTransientChainWithIncrementedNumber() {
		givenTransaction();
		Policy policy = registeredPolicy();
		PolicyFetchAttempt latest = failedAttempt(policy, 2, PolicyFetchFailureKind.TRANSIENT);
		givenLatest(latest);
		when(attemptRepository.saveAndFlush(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptRepository.claimPendingAttempt(any(), any(), any(), any())).thenReturn(1);
		when(attemptRepository.findById(any()))
				.thenReturn(Optional.of(startedAttempt()));

		service.beginAttempt(policy, PolicyFetchAttemptTrigger.SCHEDULED);

		ArgumentCaptor<PolicyFetchAttempt> captor = ArgumentCaptor.forClass(PolicyFetchAttempt.class);
		verify(attemptRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getAttemptNumber()).isEqualTo(3);
	}

	@Test
	void beginAttemptResetsChainAfterPermanentFailure() {
		givenTransaction();
		Policy policy = registeredPolicy();
		givenLatest(failedAttempt(policy, 3, PolicyFetchFailureKind.PERMANENT));
		when(attemptRepository.saveAndFlush(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptRepository.claimPendingAttempt(any(), any(), any(), any())).thenReturn(1);
		when(attemptRepository.findById(any()))
				.thenReturn(Optional.of(startedAttempt()));

		service.beginAttempt(policy, PolicyFetchAttemptTrigger.MANUAL);

		ArgumentCaptor<PolicyFetchAttempt> captor = ArgumentCaptor.forClass(PolicyFetchAttempt.class);
		verify(attemptRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getAttemptNumber()).isEqualTo(1);
	}

	@Test
	void beginAttemptResetsChainAfterExhaustedTransientFailure() {
		givenTransaction();
		Policy policy = registeredPolicy();
		givenLatest(failedAttempt(policy, 5, PolicyFetchFailureKind.TRANSIENT));
		when(attemptRepository.saveAndFlush(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptRepository.claimPendingAttempt(any(), any(), any(), any())).thenReturn(1);
		when(attemptRepository.findById(any()))
				.thenReturn(Optional.of(startedAttempt()));

		service.beginAttempt(policy, PolicyFetchAttemptTrigger.SCHEDULED);

		ArgumentCaptor<PolicyFetchAttempt> captor = ArgumentCaptor.forClass(PolicyFetchAttempt.class);
		verify(attemptRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getAttemptNumber()).isEqualTo(1);
	}

	@Test
	void beginAttemptResetsChainAfterSuccess() {
		givenTransaction();
		Policy policy = registeredPolicy();
		PolicyFetchAttempt latest = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.SCHEDULED, 4, clock.instant());
		latest.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 8L, null, null, clock.instant());
		givenLatest(latest);
		when(attemptRepository.saveAndFlush(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptRepository.claimPendingAttempt(any(), any(), any(), any())).thenReturn(1);
		when(attemptRepository.findById(any()))
				.thenReturn(Optional.of(startedAttempt()));

		service.beginAttempt(policy, PolicyFetchAttemptTrigger.MANUAL);

		ArgumentCaptor<PolicyFetchAttempt> captor = ArgumentCaptor.forClass(PolicyFetchAttempt.class);
		verify(attemptRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getAttemptNumber()).isEqualTo(1);
	}

	@Test
	void beginAttemptResetsChainAfterUnclassifiedLegacyFailure() {
		givenTransaction();
		Policy policy = registeredPolicy();
		// Pre-2U.1 FAILED rows carry a NULL kind in the database (loaded
		// by Hibernate without running the transition guard); they reset
		// the chain.
		PolicyFetchAttempt legacy = mock(PolicyFetchAttempt.class);
		when(legacy.getStatus()).thenReturn(PolicyFetchAttemptStatus.FAILED);
		when(legacy.getFailureKind()).thenReturn(null);
		givenLatest(legacy);
		when(attemptRepository.saveAndFlush(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptRepository.claimPendingAttempt(any(), any(), any(), any())).thenReturn(1);
		when(attemptRepository.findById(any()))
				.thenReturn(Optional.of(startedAttempt()));

		service.beginAttempt(policy, PolicyFetchAttemptTrigger.MANUAL);

		ArgumentCaptor<PolicyFetchAttempt> captor = ArgumentCaptor.forClass(PolicyFetchAttempt.class);
		verify(attemptRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getAttemptNumber()).isEqualTo(1);
	}

	@Test
	void markSucceededCompletesWithExactDuration() {
		givenTransaction();
		UUID attemptId = UUID.randomUUID();
		PolicyFetchAttempt stored = startedAttempt();
		when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(stored));
		when(attemptRepository.save(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		clock.advance(Duration.ofMillis(1500));

		PolicyFetchAttempt completed =
				service.markSucceeded(attemptId, 200, 512L);

		assertThat(completed.getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(completed.getHttpStatus()).isEqualTo(200);
		assertThat(completed.getBytesFetched()).isEqualTo(512L);
		assertThat(completed.getErrorMessage()).isNull();
		assertThat(completed.getCompletedAt()).isEqualTo(clock.instant());
		assertThat(completed.getDurationMs()).isEqualTo(1500L);
		verify(attemptRepository).save(stored);
	}

	@Test
	void markSkippedUnchangedCompletesWithoutError() {
		givenTransaction();
		UUID attemptId = UUID.randomUUID();
		PolicyFetchAttempt stored = startedAttempt();
		when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(stored));
		when(attemptRepository.save(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		PolicyFetchAttempt completed =
				service.markSkippedUnchanged(attemptId, 200, 64L);

		assertThat(completed.getStatus()).isEqualTo(PolicyFetchAttemptStatus.SKIPPED_UNCHANGED);
		assertThat(completed.getHttpStatus()).isEqualTo(200);
		assertThat(completed.getBytesFetched()).isEqualTo(64L);
		assertThat(completed.getDurationMs()).isZero();
	}

	@Test
	void markFailedCompletesWithMessageAndNullables() {
		givenTransaction();
		UUID attemptId = UUID.randomUUID();
		PolicyFetchAttempt stored = startedAttempt();
		when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(stored));
		when(attemptRepository.save(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		PolicyFetchAttempt completed =
				service.markFailed(attemptId, null, null, "connection refused",
						PolicyFetchFailureKind.TRANSIENT);

		assertThat(completed.getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(completed.getHttpStatus()).isNull();
		assertThat(completed.getBytesFetched()).isNull();
		assertThat(completed.getErrorMessage()).isEqualTo("connection refused");
		assertThat(completed.getFailureKind()).isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		assertThat(completed.getCompletedAt()).isEqualTo(clock.instant());
	}

	@Test
	void markFailedRecordsPermanentKind() {
		givenTransaction();
		UUID attemptId = UUID.randomUUID();
		PolicyFetchAttempt stored = startedAttempt();
		when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(stored));
		when(attemptRepository.save(any(PolicyFetchAttempt.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		PolicyFetchAttempt completed =
				service.markFailed(attemptId, 404, 32L, "not found",
						PolicyFetchFailureKind.PERMANENT);

		assertThat(completed.getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(completed.getHttpStatus()).isEqualTo(404);
		assertThat(completed.getFailureKind()).isEqualTo(PolicyFetchFailureKind.PERMANENT);
	}

	@Test
	void completingUnknownAttemptFails() {
		givenTransaction();
		UUID unknown = UUID.randomUUID();
		when(attemptRepository.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.markSucceeded(unknown, 200, 1L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("PolicyFetchAttempt not found");
	}

	@Test
	void completingTerminalAttemptPropagatesGuard() {
		givenTransaction();
		UUID attemptId = UUID.randomUUID();
		PolicyFetchAttempt stored = startedAttempt();
		stored.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1L, null, null, clock.instant());
		when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(stored));

		assertThatThrownBy(() -> service.markFailed(attemptId, 200, 1L, "late",
				PolicyFetchFailureKind.TRANSIENT))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nullArgumentsRejected() {
		Policy policy = registeredPolicy();
		RetryPolicy retryPolicy = new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(0));
		assertThatThrownBy(() -> service.beginAttempt(null, PolicyFetchAttemptTrigger.MANUAL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.beginAttempt(policy, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.markSucceeded(null, 200, 1L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.markSkippedUnchanged(null, 200, 1L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> service.markFailed(null, 200, 1L, "boom", PolicyFetchFailureKind.TRANSIENT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttemptService(null, transactionManager, clock, retryPolicy))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttemptService(attemptRepository, null, clock, retryPolicy))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new PolicyFetchAttemptService(attemptRepository, transactionManager, null, retryPolicy))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new PolicyFetchAttemptService(attemptRepository, transactionManager, clock, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private PolicyFetchAttempt failedAttempt(Policy policy, int attemptNumber,
			PolicyFetchFailureKind kind) {
		PolicyFetchAttempt latest = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.SCHEDULED, attemptNumber, clock.instant());
		latest.complete(PolicyFetchAttemptStatus.FAILED, null, null, "down", kind, clock.instant());
		return latest;
	}

	private PolicyFetchAttempt startedAttempt() {
		return new PolicyFetchAttempt(registeredPolicy(),
				PolicyFetchAttemptTrigger.MANUAL, 1, clock.instant());
	}

	private Policy registeredPolicy() {
		Policy policy = new Policy("P", "https://example.com/p");
		policy.setId(UUID.randomUUID());
		return policy;
	}

	/** Deterministic manually advanced clock for exact duration assertions. */
	private static final class ManualClock extends Clock {

		private Instant now;

		ManualClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
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
