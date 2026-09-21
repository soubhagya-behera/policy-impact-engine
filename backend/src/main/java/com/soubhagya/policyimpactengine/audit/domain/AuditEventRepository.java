package com.soubhagya.policyimpactengine.audit.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Phase 11A — persistence for {@link AuditEvent}.
 *
 * <p>Append-only reads: the actor-scoped feed and the chain-head
 * lookup. No update/delete operations are exposed. The head query
 * returns the single childless row (empty when the chain is empty);
 * under the {@code UNIQUE(prev_hash)} + single-genesis invariants at
 * most one row ever matches.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

	List<AuditEvent> findByActorUser_IdOrderByOccurredAtDescIdDesc(UUID actorUserId);

	/**
	 * Returns the current chain head: the row whose hash no other row
	 * claims as predecessor. Ordering is a defensive tie-break only —
	 * the invariants guarantee at most one row.
	 */
	@Query("SELECT a FROM AuditEvent a WHERE a.eventHash NOT IN "
			+ "(SELECT c.prevHash FROM AuditEvent c WHERE c.prevHash IS NOT NULL) "
			+ "ORDER BY a.occurredAt DESC, a.id DESC")
	List<AuditEvent> findChainHeads();
}
