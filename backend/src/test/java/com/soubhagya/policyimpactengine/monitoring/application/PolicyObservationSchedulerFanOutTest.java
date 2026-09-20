package com.soubhagya.policyimpactengine.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.SimHashSimilarity;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.notification.application.NotificationFanOutService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;

/**
 * Phase 10B-1 — scheduler fan-out trigger tests.
 *
 * <p>No Spring context, no database, no network. Verifies the tick
 * captures the observation result, calls the fan-out service after a
 * successful observation, never fans out on failure or claim-skip,
 * and isolates fan-out exceptions per policy without aborting the
 * tick or skipping the uniform next-check advancement.
 */
@ExtendWith(MockitoExtension.class)
class PolicyObservationSchedulerFanOutTest {

	private static final Instant TICK_START = Instant.parse("2026-09-18T10:00:00Z");
	private static final Duration INTERVAL = Duration.ofHours(24);

	@Mock
	private PolicyRepository policyRepository;

	@Mock
	private PolicyObservationService observationService;

	@Mock
	private NotificationFanOutService fanOutService;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private PolicyObservationScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new PolicyObservationScheduler(policyRepository, observationService,
				fanOutService, transactionManager, Clock.fixed(TICK_START, ZoneOffset.UTC),
				INTERVAL);
	}

	@Test
	void successfulObservationTriggersFanOut() {
		Policy policy = policy();
		PolicyObservationResult result = result(policy.getId(),
				PolicyVersionObservationOutcome.NEW_VERSION, 2);
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(policy));
		when(observationService.observe(policy.getId(), PolicyFetchAttemptTrigger.SCHEDULED))
				.thenReturn(result);
		givenTransaction();
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		scheduler.checkDuePolicies();

		verify(fanOutService).fanOut(policy.getId(), result);
		assertThat(policy.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
	}

	@Test
	void failedObservationNeverFansOutButStillAdvances() {
		Policy policy = policy();
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(policy));
		when(observationService.observe(policy.getId(), PolicyFetchAttemptTrigger.SCHEDULED))
				.thenThrow(new IllegalStateException("fetch down"));
		givenTransaction();
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		scheduler.checkDuePolicies();

		verify(fanOutService, never()).fanOut(any(), any());
		assertThat(policy.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
	}

	@Test
	void claimSkipNeverFansOutButStillAdvances() {
		Policy policy = policy();
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(policy));
		when(observationService.observe(policy.getId(), PolicyFetchAttemptTrigger.SCHEDULED))
				.thenThrow(new PolicyFetchClaimRejectedException(policy.getId()));
		givenTransaction();
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		scheduler.checkDuePolicies();

		verify(fanOutService, never()).fanOut(any(), any());
		assertThat(policy.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
	}

	@Test
	void fanOutExceptionIsIsolatedAndTickContinues() {
		Policy failing = policy();
		Policy healthy = policy();
		PolicyObservationResult first = result(failing.getId(),
				PolicyVersionObservationOutcome.NEW_VERSION, 2);
		PolicyObservationResult second = result(healthy.getId(),
				PolicyVersionObservationOutcome.NEW_VERSION, 2);
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(failing, healthy));
		when(observationService.observe(eq(failing.getId()),
				eq(PolicyFetchAttemptTrigger.SCHEDULED))).thenReturn(first);
		when(observationService.observe(eq(healthy.getId()),
				eq(PolicyFetchAttemptTrigger.SCHEDULED))).thenReturn(second);
		doThrow(new IllegalStateException("fan-out boom")).when(fanOutService)
				.fanOut(eq(failing.getId()), eq(first));
		givenTransaction();
		when(policyRepository.findById(failing.getId())).thenReturn(Optional.of(failing));
		when(policyRepository.findById(healthy.getId())).thenReturn(Optional.of(healthy));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		scheduler.checkDuePolicies();

		verify(fanOutService).fanOut(failing.getId(), first);
		verify(fanOutService).fanOut(healthy.getId(), second);
		assertThat(failing.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
		assertThat(healthy.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
	}

	private void givenTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	private Policy policy() {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setId(UUID.randomUUID());
		policy.setNextCheckAt(TICK_START.minusSeconds(60));
		return policy;
	}

	private PolicyObservationResult result(UUID policyId,
			PolicyVersionObservationOutcome outcome, int versionNumber) {
		return new PolicyObservationResult(policyId, outcome, versionNumber, "hash",
				Optional.of(new PolicyDiffResult(java.util.List.of())),
				Optional.of(new SimHashSimilarity(0L, 0L, 0, 1.0)));
	}
}
