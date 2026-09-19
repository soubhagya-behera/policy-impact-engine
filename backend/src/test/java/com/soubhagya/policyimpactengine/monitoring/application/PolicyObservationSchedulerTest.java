package com.soubhagya.policyimpactengine.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;

/**
 * Phase 2T — deterministic unit tests for the scheduled observation tick.
 *
 * <p>No Spring context, no database, no network. A fixed clock makes the
 * tick start and every advancement exact.
 */
@ExtendWith(MockitoExtension.class)
class PolicyObservationSchedulerTest {

	private static final Instant TICK_START = Instant.parse("2026-09-18T10:00:00Z");
	private static final Duration INTERVAL = Duration.ofHours(24);

	@Mock
	private PolicyRepository policyRepository;

	@Mock
	private PolicyObservationService observationService;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private PolicyObservationScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new PolicyObservationScheduler(policyRepository, observationService,
				transactionManager, Clock.fixed(TICK_START, ZoneOffset.UTC), INTERVAL);
	}

	@Test
	void tickObservesDuePoliciesInOrderWithScheduledTrigger() {
		Policy first = policy("First");
		Policy second = policy("Second");
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(first, second));
		givenTransaction();
		when(policyRepository.findById(first.getId())).thenReturn(Optional.of(first));
		when(policyRepository.findById(second.getId())).thenReturn(Optional.of(second));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		scheduler.checkDuePolicies();

		InOrder order = inOrder(observationService, policyRepository);
		order.verify(observationService).observe(first.getId(), PolicyFetchAttemptTrigger.SCHEDULED);
		order.verify(policyRepository).save(first);
		order.verify(observationService).observe(second.getId(), PolicyFetchAttemptTrigger.SCHEDULED);
		order.verify(policyRepository).save(second);
		assertThat(first.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
		assertThat(second.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
	}

	@Test
	void onePolicyFailureDoesNotStopRemainingPolicies() {
		Policy failing = policy("Failing");
		Policy healthy = policy("Healthy");
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(failing, healthy));
		givenTransaction();
		when(policyRepository.findById(failing.getId())).thenReturn(Optional.of(failing));
		when(policyRepository.findById(healthy.getId())).thenReturn(Optional.of(healthy));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(observationService.observe(eq(failing.getId()), eq(PolicyFetchAttemptTrigger.SCHEDULED)))
				.thenThrow(new IllegalStateException("fetch down"));

		scheduler.checkDuePolicies();

		verify(observationService).observe(healthy.getId(), PolicyFetchAttemptTrigger.SCHEDULED);
		// Uniform advancement applies to the failed policy as well: no
		// backoff exists in Phase 2T.
		assertThat(failing.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
		assertThat(healthy.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
	}

	@Test
	void lostClaimSkipsPolicyForThisCycleButStillAdvancesNextCheck() {
		Policy claimed = policy("Claimed");
		Policy healthy = policy("Healthy");
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(claimed, healthy));
		givenTransaction();
		when(policyRepository.findById(claimed.getId())).thenReturn(Optional.of(claimed));
		when(policyRepository.findById(healthy.getId())).thenReturn(Optional.of(healthy));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(observationService.observe(eq(claimed.getId()), eq(PolicyFetchAttemptTrigger.SCHEDULED)))
				.thenThrow(new PolicyFetchClaimRejectedException(claimed.getId()));

		scheduler.checkDuePolicies();

		verify(observationService).observe(healthy.getId(), PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(claimed.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
		assertThat(healthy.getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
	}

		@Test
	void emptyDueListTouchesNothing() {
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of());

		scheduler.checkDuePolicies();

		verifyNoInteractions(observationService);
		verify(policyRepository, never()).save(any(Policy.class));
	}

	@Test
	void eachPolicyObservedAtMostOncePerTick() {
		Policy only = policy("Only");
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(only));
		givenTransaction();
		when(policyRepository.findById(only.getId())).thenReturn(Optional.of(only));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		scheduler.checkDuePolicies();

		verify(observationService, times(1))
				.observe(only.getId(), PolicyFetchAttemptTrigger.SCHEDULED);
	}

	@Test
	void missingPolicyDuringAdvancePropagates() {
		Policy gone = policy("Gone");
		when(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, TICK_START))
				.thenReturn(List.of(gone));
		givenTransaction();
		when(policyRepository.findById(gone.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> scheduler.checkDuePolicies())
				.isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void nullAndNonPositiveArgumentsRejected() {
		Clock clock = Clock.fixed(TICK_START, ZoneOffset.UTC);
		assertThatThrownBy(() -> new PolicyObservationScheduler(null, observationService,
				transactionManager, clock, INTERVAL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyObservationScheduler(policyRepository, null,
				transactionManager, clock, INTERVAL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyObservationScheduler(policyRepository,
				observationService, null, clock, INTERVAL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyObservationScheduler(policyRepository,
				observationService, transactionManager, null, INTERVAL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyObservationScheduler(policyRepository,
				observationService, transactionManager, clock, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyObservationScheduler(policyRepository,
				observationService, transactionManager, clock, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyObservationScheduler(policyRepository,
				observationService, transactionManager, clock, Duration.ofHours(-1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private void givenTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	private Policy policy(String name) {
		Policy policy = new Policy(name, "https://example.com/" + UUID.randomUUID());
		policy.setId(UUID.randomUUID());
		return policy;
	}
}
