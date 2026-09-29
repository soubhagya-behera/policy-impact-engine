package com.soubhagya.policyimpactengine.policy.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link Policy}.
 *
 * <p>Phase 1 needs no custom queries. Phase 2T adds deterministic
 * due-policy selection for the monitoring scheduler: ACTIVE policies
 * whose next check time has elapsed, ordered by next check time, then
 * id as the final tie-break.
 *
 * <p>Authenticated policy hardening adds owner-scoped reads: a user
 * sees only their own policies, in registration (creation) order with
 * id as the final tie-break. Filtering happens here, never in Java.
 */
public interface PolicyRepository extends JpaRepository<Policy, UUID> {

	/**
	 * Returns policies with the given status whose next check time is at
	 * or before {@code now}, in deterministic
	 * (next_check_at, id) order.
	 */
	List<Policy> findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
			PolicyStatus status, Instant now);

	/**
	 * Returns the policy only when it is owned by the given user.
	 * Backs user-scoped retrieval: foreign rows behave as not-found.
	 */
	Optional<Policy> findByIdAndOwner_Id(UUID id, UUID ownerId);

	/**
	 * Returns the user's policies in registration order, oldest first.
	 */
	List<Policy> findByOwner_IdOrderByCreatedAtAscIdAsc(UUID ownerId);

	/**
	 * Phase 13-B — paginated owner-scoped read. No ordering is embedded
	 * in the name: the caller supplies the exact deterministic Sort
	 * (createdAt ASC, id ASC) through the Pageable, so the ordering
	 * lives in exactly one place. Returns a bare list (limit/offset
	 * only, no count query).
	 */
	List<Policy> findByOwner_Id(UUID ownerId, Pageable pageable);

	/**
	 * Phase 14-C/2 — atomic archive election: transitions exactly one
	 * owned {@code ACTIVE} policy to {@code ARCHIVED}. The {@code id} +
	 * {@code owner_id} + {@code status} conjuncts make ownership and
	 * transition atomic: a foreign, unknown, or already-archived row
	 * matches nothing. The persistence context is cleared so callers
	 * re-read the freshly archived row instead of a stale pre-archive
	 * instance.
	 *
	 * @return 1 when this caller won the transition (it must then emit
	 *         the single {@code POLICY_ARCHIVED} audit event), 0 when
	 *         the row was foreign, unknown, or already archived (the
	 *         caller must emit nothing)
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE Policy policy SET policy.status = :archived"
			+ " WHERE policy.id = :id AND policy.owner.id = :ownerId"
			+ " AND policy.status = :active")
	int archiveOwnedActive(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
			@Param("active") PolicyStatus active, @Param("archived") PolicyStatus archived);

}
