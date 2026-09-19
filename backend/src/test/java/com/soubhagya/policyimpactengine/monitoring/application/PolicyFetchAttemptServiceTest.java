package com.soubhagya.policyimpactengine.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
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
import com.soubhagya.policyimpactengine.policy.domain.Policy;

/**
 * Phase 2S — deterministic unit tests for the attempt lifecycle service.
 *
 * <p>No Spring context, no database, no network. A manually advanced clock
 * makes durations exact; the transaction manager is a Mockito mock following
 * the established {@code TransactionTemplate} unit-test pattern.
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
		service = new PolicyFetchAttemptService(attemptRepository, transactionManager, clock);
	}

	private void givenTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	@Test
	void beginAttemptClaimsPendingCandidateAsInProgressWithAttemptNumberOne() {
		givenTransaction();
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
				service.markFailed(attemptId, null, null, "connection refused");

		assertThat(completed.getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(completed.getHttpStatus()).isNull();
		assertThat(completed.getBytesFetched()).isNull();
		assertThat(completed.getErrorMessage()).isEqualTo("connection refused");
		assertThat(completed.getCompletedAt()).isEqualTo(clock.instant());
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
		stored.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1L, null, clock.instant());
		when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(stored));

		assertThatThrownBy(() -> service.markFailed(attemptId, 200, 1L, "late"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nullArgumentsRejected() {
		Policy policy = registeredPolicy();
		assertThatThrownBy(() -> service.beginAttempt(null, PolicyFetchAttemptTrigger.MANUAL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.beginAttempt(policy, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.markSucceeded(null, 200, 1L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.markSkippedUnchanged(null, 200, 1L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> service.markFailed(null, 200, 1L, "boom"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttemptService(null, transactionManager, clock))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyFetchAttemptService(attemptRepository, null, clock))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new PolicyFetchAttemptService(attemptRepository, transactionManager, null))
				.isInstanceOf(IllegalArgumentException.class);
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
