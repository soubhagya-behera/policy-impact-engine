package com.soubhagya.policyimpactengine.policy.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Policy}.
 *
 * <p>Phase 1 needs no custom queries. Phase 2T adds deterministic
 * due-policy selection for the monitoring scheduler: ACTIVE policies
 * whose next check time has elapsed, ordered by next check time, then
 * id as the final tie-break.
 */
public interface PolicyRepository extends JpaRepository<Policy, UUID> {

	/**
	 * Returns policies with the given status whose next check time is at
	 * or before {@code now}, in deterministic
	 * (next_check_at, id) order.
	 */
	List<Policy> findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
			PolicyStatus status, Instant now);

}
