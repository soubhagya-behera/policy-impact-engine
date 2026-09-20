package com.soubhagya.policyimpactengine.monitoring.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;

/**
 * Phase 2U.2 — recovery of stale {@code IN_PROGRESS} observation attempts.
 *
 * <p>A worker that crashes (or otherwise disappears) between claiming an
 * attempt and completing it leaves an {@code IN_PROGRESS} row that holds
 * the policy's V11 in-flight slot forever, blocking every later check of
 * that policy. This service reaps such orphans on a schedule: a row still
 * {@code IN_PROGRESS} once its claim/lease start ({@code started_at}) is
 * at or past the configured lease ({@code monitoring.stale-timeout}) is
 * transitioned in place to {@code FAILED}/{@code TRANSIENT} and its policy
 * is rescheduled through the unchanged Phase 2U.1 retry machinery, so the
 * next check runs later as an ordinary observation through the single
 * shared claim path.
 *
 * <p>What recovery is not: no new attempt row is created, no row is
 * deleted, no new status exists, the attempt number is preserved, and
 * {@code PENDING} rows are never touched (a committed-but-unclaimed
 * {@code PENDING} row cannot exist because insert and claim share one
 * transaction). The sweeper performs no HTTP/network I/O and holds no
 * transaction across HTTP — it never fetches at all.
 *
 * <p>Concurrency across application instances uses database conditional
 * updates only, never Java synchronization: the recovery UPDATE carries
 * {@code WHERE id AND status = 'IN_PROGRESS' AND started_at <= cutoff},
 * so exactly one racing recovery (or the original worker completing
 * first) wins, decided by the affected-row count. A lost race performs no
 * further work — in particular no {@code next_check_at} write.
 */
@Service
public class StaleAttemptRecovery {

	/**
	 * Prefix of every stale-recovery error message. The recovered row keeps
	 * {@code failure_kind = TRANSIENT} so retry math is unchanged; this
	 * prefix is how reaped rows are distinguished from genuine transient
	 * failures in history.
	 */
	static final String STALE_MESSAGE_PREFIX = "Stale IN_PROGRESS";

	private final PolicyFetchAttemptRepository attemptRepository;
	private final PolicyRepository policyRepository;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;
	private final RetryPolicy retryPolicy;
	private final Duration staleTimeout;
	private final int batchSize;

	public StaleAttemptRecovery(PolicyFetchAttemptRepository attemptRepository,
			PolicyRepository policyRepository,
			PlatformTransactionManager transactionManager,
			Clock clock,
			RetryPolicy retryPolicy,
			@Value("${monitoring.stale-timeout:PT30M}") Duration staleTimeout,
			@Value("${monitoring.stale-batch-size:100}") int batchSize) {
		if (attemptRepository == null) {
			throw new IllegalArgumentException("Attempt repository must not be null");
		}
		if (policyRepository == null) {
			throw new IllegalArgumentException("Policy repository must not be null");
		}
		if (transactionManager == null) {
			throw new IllegalArgumentException("Transaction manager must not be null");
		}
		if (clock == null) {
			throw new IllegalArgumentException("Clock must not be null");
		}
		if (retryPolicy == null) {
			throw new IllegalArgumentException("Retry policy must not be null");
		}
		if (staleTimeout == null || staleTimeout.isZero() || staleTimeout.isNegative()) {
			throw new IllegalArgumentException("Stale timeout must be a positive duration");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("Batch size must be >= 1");
		}
		this.attemptRepository = attemptRepository;
		this.policyRepository = policyRepository;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.retryPolicy = retryPolicy;
		this.staleTimeout = staleTimeout;
		this.batchSize = batchSize;
	}

	/**
	 * Scheduled stale-recovery sweep. Each run reaps at most
	 * {@code monitoring.stale-batch-size} stale attempts, oldest first,
	 * with per-row failure isolation: one poison row never aborts the
	 * remaining candidates.
	 */
	@Scheduled(fixedDelayString = "${monitoring.stale-check-interval:PT5M}")
	public void recoverStaleAttemptsScheduled() {
		recoverStaleAttempts(clock.instant());
	}

	/**
	 * Recovers the stale attempts visible at {@code now}: every
	 * {@code IN_PROGRESS} row with {@code started_at <= now - staleTimeout}
	 * (exactly-at-threshold counts as stale) is transitioned to
	 * {@code FAILED}/{@code TRANSIENT} and its policy rescheduled.
	 *
	 * @param now the recovery time; the stale cutoff is derived from it
	 * @return how many attempts this run recovered
	 */
	public int recoverStaleAttempts(Instant now) {
		if (now == null) {
			throw new IllegalArgumentException("Recovery time must not be null");
		}
		Instant cutoff = now.minus(staleTimeout);
		List<PolicyFetchAttempt> candidates = attemptRepository
				.findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
						PolicyFetchAttemptStatus.IN_PROGRESS, cutoff,
						PageRequest.of(0, batchSize));
		int recovered = 0;
		for (PolicyFetchAttempt candidate : candidates) {
			try {
				if (recoverOne(candidate.getId(), now, cutoff)) {
					recovered++;
				}
			}
			catch (RuntimeException rowFailure) {
				// Per-row isolation: a poison candidate must not abort the
				// remaining stale rows in this run.
			}
		}
		return recovered;
	}

	/**
	 * Recovers a single candidate: conditional terminal update first, and
	 * only when that update wins (affected rows == 1) the policy
	 * reschedule through the unchanged {@link RetryPolicy} in a second
	 * short transaction. A lost race returns {@code false} without
	 * touching the policy.
	 */
	private boolean recoverOne(UUID attemptId, Instant now, Instant cutoff) {
		if (attemptId == null) {
			throw new IllegalArgumentException("Attempt id must not be null");
		}
		PolicyFetchAttempt snapshot = attemptRepository.findById(attemptId).orElse(null);
		if (snapshot == null
				|| snapshot.getStatus() != PolicyFetchAttemptStatus.IN_PROGRESS
				|| snapshot.getStartedAt().isAfter(cutoff)) {
			return false;
		}
		UUID policyId = snapshot.getPolicy().getId();
		int attemptNumber = snapshot.getAttemptNumber();
		String message = STALE_MESSAGE_PREFIX + " attempt abandoned after " + staleTimeout
				+ ": " + snapshot.getTrigger() + " attempt " + attemptNumber
				+ " started at " + snapshot.getStartedAt()
				+ " with no terminal outcome; presumed crashed worker.";
		long durationMs = Math.max(0L, Duration.between(snapshot.getStartedAt(), now).toMillis());
		Integer affected = writeTransaction.execute(status -> attemptRepository.recoverStaleAttempt(
				attemptId, PolicyFetchAttemptStatus.IN_PROGRESS, cutoff,
				PolicyFetchAttemptStatus.FAILED, PolicyFetchFailureKind.TRANSIENT,
				message, now, durationMs));
		if (affected == null || affected != 1) {
			return false;
		}
		Instant nextCheckAt = retryPolicy.nextCheckAt(now, PolicyFetchFailureKind.TRANSIENT,
				attemptNumber);
		writeTransaction.execute(status -> {
			Policy policy = policyRepository.findById(policyId)
					.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));
			policy.setNextCheckAt(nextCheckAt);
			return policyRepository.save(policy);
		});
		return true;
	}
}
