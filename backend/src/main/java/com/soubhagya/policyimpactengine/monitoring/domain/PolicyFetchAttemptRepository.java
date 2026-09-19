package com.soubhagya.policyimpactengine.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 2S — persistence for {@link PolicyFetchAttempt}.
 *
 * <p>Only the minimum operations required by Phase 2S are exposed:
 * standard save support from {@link JpaRepository} (used inside the short
 * attempt transactions owned by the attempt service) and deterministic
 * newest-first retrieval of one policy's attempt history. No update,
 * custom delete, scheduling, or retry logic lives here. Phase 2U adds the
 * single conditional claim update; claiming runs inside the short claim
 * transactions owned by the attempt service, never across HTTP.
 */
public interface PolicyFetchAttemptRepository extends JpaRepository<PolicyFetchAttempt, UUID> {

	/**
	 * Returns the recorded attempts for the given policy, newest first.
	 */
	List<PolicyFetchAttempt> findByPolicy_IdOrderByStartedAtDesc(UUID policyId);

	/**
	 * Phase 2U atomic claim: promotes exactly one {@code PENDING} attempt to
	 * {@code IN_PROGRESS}, stamping the claim time as the observation start.
	 * The persistence context is cleared so callers re-read the freshly
	 * claimed row instead of the stale pre-claim instance.
	 *
	 * @return 1 when this caller won the claim, 0 when the row was already
	 *         claimed, completed, or gone (the caller must not fetch)
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE PolicyFetchAttempt attempt SET attempt.status = :claimed, attempt.startedAt = :claimedAt"
			+ " WHERE attempt.id = :attemptId AND attempt.status = :expected")
	int claimPendingAttempt(@Param("attemptId") UUID attemptId,
			@Param("expected") PolicyFetchAttemptStatus expected,
			@Param("claimed") PolicyFetchAttemptStatus claimed,
			@Param("claimedAt") Instant claimedAt);

}
