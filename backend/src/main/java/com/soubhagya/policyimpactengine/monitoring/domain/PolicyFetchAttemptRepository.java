package com.soubhagya.policyimpactengine.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
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
 * transactions owned by the attempt service, never across HTTP. Phase
 * 2U.2 adds the stale-candidate selection and the single conditional
 * stale-recovery update; recovery runs inside the short recovery
 * transactions owned by the stale-recovery service, never across HTTP,
 * and never touches {@code PENDING} rows.
 */
public interface PolicyFetchAttemptRepository extends JpaRepository<PolicyFetchAttempt, UUID> {

	/**
	 * Returns the recorded attempts for the given policy, newest first.
	 */
	List<PolicyFetchAttempt> findByPolicy_IdOrderByStartedAtDesc(UUID policyId);

	/**
	 * Returns the newest recorded attempt for the given policy, if any.
	 * Phase 2U.1 derives the retry chain position from this row: a
	 * {@code FAILED}/{@code TRANSIENT} row continues the chain, anything
	 * else (or no row) starts it at 1.
	 */
	Optional<PolicyFetchAttempt> findFirstByPolicy_IdOrderByStartedAtDesc(UUID policyId);

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

	/**
	 * Phase 2U.2 stale-candidate selection: {@code IN_PROGRESS} attempts
	 * whose claim/lease start is at or before {@code cutoff}, oldest
	 * first. The caller pages through {@code pageable} to bound each
	 * sweeper run. Terminal rows and {@code PENDING} rows are never
	 * returned; {@code PENDING} recovery is explicitly out of scope
	 * because a committed-but-unclaimed {@code PENDING} row cannot exist
	 * (insert and claim share one transaction).
	 */
	List<PolicyFetchAttempt> findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
			PolicyFetchAttemptStatus status, Instant cutoff, Pageable pageable);

	/**
	 * Phase 2U.2 stale recovery: transitions exactly one stale
	 * {@code IN_PROGRESS} attempt to {@code FAILED}/{@code TRANSIENT},
	 * stamping the recovery time as the completion. The
	 * {@code status} + {@code started_at} conjuncts make detection and
	 * transition atomic: a row that completed concurrently, or whose
	 * lease has not actually expired, matches nothing. The persistence
	 * context is cleared so callers re-read the freshly recovered row
	 * instead of the stale pre-recovery instance.
	 *
	 * @return 1 when this caller won the recovery (it must then
	 *         reschedule the policy), 0 when the row was already
	 *         completed, recovered elsewhere, or never stale (the caller
	 *         must do nothing further, especially no
	 *         {@code next_check_at} write)
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE PolicyFetchAttempt attempt SET attempt.status = :terminal,"
			+ " attempt.failureKind = :failureKind, attempt.errorMessage = :errorMessage,"
			+ " attempt.completedAt = :completedAt, attempt.durationMs = :durationMs"
			+ " WHERE attempt.id = :attemptId AND attempt.status = :expected"
			+ " AND attempt.startedAt <= :cutoff")
	int recoverStaleAttempt(@Param("attemptId") UUID attemptId,
			@Param("expected") PolicyFetchAttemptStatus expected,
			@Param("cutoff") Instant cutoff,
			@Param("terminal") PolicyFetchAttemptStatus terminal,
			@Param("failureKind") PolicyFetchFailureKind failureKind,
			@Param("errorMessage") String errorMessage,
			@Param("completedAt") Instant completedAt,
			@Param("durationMs") long durationMs);

}
