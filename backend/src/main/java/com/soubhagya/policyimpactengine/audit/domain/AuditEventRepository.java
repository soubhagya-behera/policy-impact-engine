package com.soubhagya.policyimpactengine.audit.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/**
 * Phase 11A — persistence for {@link AuditEvent}.
 *
 * <p>Append-only reads: the actor-scoped feed and the chain-head
 * lookup. No update/delete operations are exposed. The head query
 * returns the single childless row (empty when the chain is empty);
 * under the {@code UNIQUE(prev_hash)} + single-genesis invariants at
 * most one row ever matches.
 *
 * <p>Phase 11B adds the verification projection: a single bulk
 * scalar read carrying every canonical field plus the actor id,
 * without initializing the lazy actor association or touching the
 * user table.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

	/**
	 * Phase 11B — scalar verification snapshot of every audit row.
	 * Closed projection: only the aliased columns are selected, so
	 * no {@code User} entity is loaded for verification.
	 */
	interface AuditVerificationRow {

		UUID getId();

		AuditEventType getEventType();

		UUID getActorUserId();

		String getResourceType();

		UUID getResourceId();

		Instant getOccurredAt();

		String getMetadata();

		String getPrevHash();

		String getEventHash();
	}

	List<AuditEvent> findByActorUser_IdOrderByOccurredAtDescIdDesc(UUID actorUserId);

	/**
	 * Phase 13-B — paginated actor-scoped feed. No ordering is embedded
	 * in the name: the caller supplies the exact deterministic Sort
	 * (occurredAt DESC, id DESC) through the Pageable. Returns a bare
	 * list (limit/offset only, no count query).
	 */
	List<AuditEvent> findByActorUser_Id(UUID actorUserId, Pageable pageable);

	/**
	 * Returns one scalar snapshot per audit row for chain
	 * verification. No ordering is imposed here: chain order comes
	 * exclusively from {@code prev_hash} linkage, never from
	 * timestamps.
	 */
	@Query("SELECT a.id AS id, a.eventType AS eventType, a.actorUser.id AS actorUserId, "
			+ "a.resourceType AS resourceType, a.resourceId AS resourceId, "
			+ "a.occurredAt AS occurredAt, a.metadata AS metadata, "
			+ "a.prevHash AS prevHash, a.eventHash AS eventHash FROM AuditEvent a")
	List<AuditVerificationRow> findAllForVerification();

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
