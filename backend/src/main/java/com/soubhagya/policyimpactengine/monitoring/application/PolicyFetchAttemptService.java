package com.soubhagya.policyimpactengine.monitoring.application;

import java.time.Clock;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.policy.domain.Policy;

/**
 * Phase 2S/2U — lifecycle and claim owner for {@link PolicyFetchAttempt} rows.
 *
 * <p>Each method runs in its own short transaction and performs no
 * HTTP/network I/O. In particular the terminal updates never join the
 * observation persistence transaction: a version/change/impact rollback
 * must still leave its {@code FAILED} attempt row behind.
 *
 * <p>Phase 2U atomic claiming: {@link #beginAttempt} is the single shared
 * claim path for both MANUAL and SCHEDULED triggers. It inserts a
 * {@code PENDING} candidate and then promotes it with the conditional
 * claim update ({@code UPDATE ... WHERE status = 'PENDING'}). The V11
 * partial unique index admits at most one {@code PENDING}/{@code IN_PROGRESS}
 * row per policy, so exactly one concurrent trigger wins: the loser's
 * insert fails with a uniqueness violation (translated to
 * {@link PolicyFetchClaimRejectedException}) and it performs no fetch and
 * leaves no second runnable row behind. No retry, backoff, jitter, or
 * stale-attempt recovery happens here; those belong to Phase 2U.1/2U.2.
 *
 * <p>{@code bytes_fetched} is an approximation: the UTF-8 byte length of
 * the decoded response body available to the orchestrator, or {@code null}
 * when no response exists. Wire bytes may differ (framing, charset);
 * exact accounting is deferred to a later phase and must not reshape the
 * fetcher in Phase 2S.
 */
@Service
public class PolicyFetchAttemptService {

	private final PolicyFetchAttemptRepository attemptRepository;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;

	public PolicyFetchAttemptService(PolicyFetchAttemptRepository attemptRepository,
			PlatformTransactionManager transactionManager, Clock clock) {
		if (attemptRepository == null) {
			throw new IllegalArgumentException("Attempt repository must not be null");
		}
		if (transactionManager == null) {
			throw new IllegalArgumentException("Transaction manager must not be null");
		}
		if (clock == null) {
			throw new IllegalArgumentException("Clock must not be null");
		}
		this.attemptRepository = attemptRepository;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Claims the policy for one observation: persists a {@code PENDING}
	 * candidate (Phase 2U attempts use {@code attemptNumber = 1}) and then
	 * atomically promotes it to {@code IN_PROGRESS} with the conditional
	 * claim update, stamping the claim time. The creation and the claim run
	 * in a single short transaction; no HTTP/network I/O happens here.
	 *
	 * <p>When another {@code PENDING} or {@code IN_PROGRESS} attempt already
	 * owns the policy, the insert collides with the V11 partial unique
	 * index and a {@link PolicyFetchClaimRejectedException} is thrown
	 * instead: MANUAL callers treat it as an explicit rejection, the
	 * scheduler treats it as a skip signal. A persistence failure (or a
	 * lost claim, defensively) propagates and the caller must not proceed
	 * with an unrecorded observation.
	 */
	public PolicyFetchAttempt beginAttempt(Policy policy, PolicyFetchAttemptTrigger trigger) {
		if (policy == null) {
			throw new IllegalArgumentException("Policy must not be null");
		}
		if (trigger == null) {
			throw new IllegalArgumentException("Trigger must not be null");
		}
		if (policy.getId() == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		UUID policyId = policy.getId();
		try {
			return writeTransaction.execute(status -> {
				PolicyFetchAttempt candidate = attemptRepository.saveAndFlush(
						PolicyFetchAttempt.pending(policy, trigger, 1, clock.instant()));
				UUID attemptId = candidate.getId();
				int claimed = attemptRepository.claimPendingAttempt(attemptId,
						PolicyFetchAttemptStatus.PENDING, PolicyFetchAttemptStatus.IN_PROGRESS,
						clock.instant());
				if (claimed != 1) {
					throw new PolicyFetchClaimRejectedException(policyId);
				}
				return attemptRepository.findById(attemptId)
						.orElseThrow(() -> new IllegalStateException(
								"Claimed attempt vanished: " + attemptId));
			});
		}
		catch (DataIntegrityViolationException collision) {
			throw new PolicyFetchClaimRejectedException(policyId, collision);
		}
	}

	/**
	 * Records a successful check.
	 */
	public PolicyFetchAttempt markSucceeded(UUID attemptId, Integer httpStatus, Long bytesFetched) {
		return complete(attemptId, PolicyFetchAttemptStatus.SUCCESS, httpStatus, bytesFetched, null);
	}

	/**
	 * Records a check whose content hashed identically to the latest
	 * version, so no new version was created.
	 */
	public PolicyFetchAttempt markSkippedUnchanged(UUID attemptId, Integer httpStatus, Long bytesFetched) {
		return complete(attemptId, PolicyFetchAttemptStatus.SKIPPED_UNCHANGED, httpStatus, bytesFetched, null);
	}

	/**
	 * Records a failed check with its cause.
	 */
	public PolicyFetchAttempt markFailed(UUID attemptId, Integer httpStatus, Long bytesFetched,
			String errorMessage) {
		return complete(attemptId, PolicyFetchAttemptStatus.FAILED, httpStatus, bytesFetched, errorMessage);
	}

	private PolicyFetchAttempt complete(UUID attemptId, PolicyFetchAttemptStatus terminal, Integer httpStatus,
			Long bytesFetched, String errorMessage) {
		if (attemptId == null) {
			throw new IllegalArgumentException("Attempt id must not be null");
		}
		return writeTransaction.execute(status -> {
			PolicyFetchAttempt attempt = attemptRepository.findById(attemptId)
					.orElseThrow(() -> new IllegalArgumentException("PolicyFetchAttempt not found: " + attemptId));
			attempt.complete(terminal, httpStatus, bytesFetched, errorMessage, clock.instant());
			return attemptRepository.save(attempt);
		});
	}
}
