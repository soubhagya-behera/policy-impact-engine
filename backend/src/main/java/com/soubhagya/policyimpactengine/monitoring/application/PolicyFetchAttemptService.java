package com.soubhagya.policyimpactengine.monitoring.application;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.policy.domain.Policy;

/**
 * Phase 2S — lifecycle owner for {@link PolicyFetchAttempt} rows.
 *
 * <p>Each method runs in its own short transaction and performs no
 * HTTP/network I/O. In particular the terminal updates never join the
 * observation persistence transaction: a version/change/impact rollback
 * must still leave its {@code FAILED} attempt row behind.
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
	 * Persists a new {@code IN_PROGRESS} attempt for the policy and returns
	 * it. Every Phase 2S attempt uses {@code attemptNumber = 1}. A
	 * persistence failure propagates and the caller must not proceed with
	 * an unrecorded observation.
	 */
	public PolicyFetchAttempt beginAttempt(Policy policy, PolicyFetchAttemptTrigger trigger) {
		if (policy == null) {
			throw new IllegalArgumentException("Policy must not be null");
		}
		if (trigger == null) {
			throw new IllegalArgumentException("Trigger must not be null");
		}
		return writeTransaction.execute(status ->
				attemptRepository.saveAndFlush(new PolicyFetchAttempt(policy, trigger, 1, clock.instant())));
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
