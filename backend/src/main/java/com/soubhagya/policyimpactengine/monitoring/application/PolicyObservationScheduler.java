package com.soubhagya.policyimpactengine.monitoring.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;

/**
 * Phase 2T — scheduled observation triggering.
 *
 * <p>Single-instance, single-threaded, sequential: each fixed-delay tick
 * loads the ACTIVE policies whose next check time has elapsed (in
 * deterministic next-check/id order) and observes each one through the
 * single shared {@link PolicyObservationService} pipeline with trigger
 * {@code SCHEDULED}. One policy is observed at most once per tick, and no
 * two checks of one policy can overlap within this phase.
 *
 * <p>Every completed check — {@code SUCCESS}, {@code SKIPPED_UNCHANGED},
 * or {@code FAILED} — advances that policy's {@code next_check_at} by
 * exactly the configured interval from the tick start. There is no
 * catch-up for long-overdue policies and no stale-attempt reclamation
 * in this phase; stale recovery belongs to Phase 2U.2 (retry and backoff
 * are Phase 2U.1, implemented in the observation failure path).
 *
 * <p>Phase 2U atomic claiming: every tick entry goes through the single
 * shared {@link PolicyObservationService} claim path, so a scheduled check
 * racing another trigger for the same policy is serialized by the V11
 * partial unique index. A lost claim surfaces as
 * {@link PolicyFetchClaimRejectedException}; the tick skips that policy
 * for this cycle (no fetch, no duplicate work) and still advances its
 * {@code next_check_at} uniformly.
 *
 * <p>Phase 2U.1 retry/backoff: a failed observation reschedules its own
 * policy through {@code next_check_at} (bounded backoff for a transient
 * failure with retries remaining, the regular interval otherwise), so the
 * tick must not blindly overwrite it. Advancement below is therefore a
 * move-guard: it applies the uniform interval only when the observation
 * left {@code next_check_at} at or before this tick's start.
 *
 * <p>The tick itself holds no database transaction. A single policy's
 * failure (already recorded as its {@code FAILED} attempt by the
 * observation flow) never aborts the remaining due policies.
 */
@Service
public class PolicyObservationScheduler {

	private final PolicyRepository policyRepository;
	private final PolicyObservationService observationService;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;
	private final Duration checkInterval;

	public PolicyObservationScheduler(PolicyRepository policyRepository,
			PolicyObservationService observationService,
			PlatformTransactionManager transactionManager,
			Clock clock,
			@Value("${monitoring.check-interval:PT24H}") Duration checkInterval) {
		if (policyRepository == null) {
			throw new IllegalArgumentException("PolicyRepository must not be null");
		}
		if (observationService == null) {
			throw new IllegalArgumentException("ObservationService must not be null");
		}
		if (transactionManager == null) {
			throw new IllegalArgumentException("Transaction manager must not be null");
		}
		if (clock == null) {
			throw new IllegalArgumentException("Clock must not be null");
		}
		if (checkInterval == null || checkInterval.isZero() || checkInterval.isNegative()) {
			throw new IllegalArgumentException("Check interval must be a positive duration");
		}
		this.policyRepository = policyRepository;
		this.observationService = observationService;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.checkInterval = checkInterval;
	}

	/**
	 * Observes every due policy once, advancing each policy's next check
	 * time by the configured interval from this tick's start — unless the
	 * observation already rescheduled it (Phase 2U.1 failure backoff),
	 * which the move-guard preserves.
	 */
	@Scheduled(fixedDelayString = "${monitoring.check-interval:PT24H}")
	public void checkDuePolicies() {
		Instant tickStart = clock.instant();
		List<Policy> due = policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, tickStart);
		for (Policy policy : due) {
			try {
				observationService.observe(policy.getId(), PolicyFetchAttemptTrigger.SCHEDULED);
			}
			catch (PolicyFetchClaimRejectedException claimedElsewhere) {
				// Another trigger owns this policy right now: skip it for
				// this cycle without fetching. Its next check still
				// advances uniformly below; stale recovery belongs to
				// Phase 2U.2.
			}
			catch (RuntimeException observationFailure) {
				// The FAILED attempt is already recorded by the observation
				// flow; continue with the remaining due policies.
			}
			advanceNextCheck(policy.getId(), tickStart);
		}
	}

	private void advanceNextCheck(UUID policyId, Instant tickStart) {
		writeTransaction.execute(status -> {
			Policy policy = policyRepository.findById(policyId)
					.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));
			// Move-guard (Phase 2U.1): a failed observation already
			// rescheduled this policy (backoff or interval) past this
			// tick's start — an interleaved MANUAL check may have done the
			// same — so leave it alone. Otherwise apply the uniform tick
			// advancement exactly as before.
			if (policy.getNextCheckAt() != null && policy.getNextCheckAt().isAfter(tickStart)) {
				return policy;
			}
			policy.setNextCheckAt(tickStart.plus(checkInterval));
			return policyRepository.save(policy);
		});
	}
}
