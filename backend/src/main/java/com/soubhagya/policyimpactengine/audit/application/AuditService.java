package com.soubhagya.policyimpactengine.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.audit.domain.AuditChain;
import com.soubhagya.policyimpactengine.audit.domain.AuditEvent;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.audit.web.dto.AuditEventResponse;
import com.soubhagya.policyimpactengine.user.domain.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Phase 11A — append-only audit writer over the single global
 * SHA-256 chain (see DECISIONS.md ADR-022/ADR-023).
 *
 * <p>Each append runs in its own dedicated short transaction after
 * the caller's business work commits: an audit failure never rolls
 * back business state. Concurrent appends serialize through the
 * database — a lost {@code UNIQUE(prev_hash)} race rolls back only
 * the audit attempt and retries against the new head with a freshly
 * recomputed candidate (never reusing the previous hash). No Java
 * synchronization; the database is the concurrency authority.
 *
 * <p>The actor association is set through an unmanaged reference
 * (no SELECT per append, no user-module edge); existence is enforced
 * by the {@code actor_user_id} foreign key at flush. The single
 * shared application {@link Clock} bean is reused deliberately: a
 * second {@code Clock} bean would make every bare-{@code Clock}
 * injection point ambiguous and break the context.
 *
 * <p>No verification (11B), no emission wiring (11C), no REST (11D).
 */
@Service
public class AuditService {

	/**
	 * Bounded predecessor-collision retry budget. Collisions resolve
	 * in one retry in practice (the winner commits before losers
	 * re-read); five attempts bound pathological contention without
	 * retrying forever. A plain constant — not configuration — because
	 * this guards a rare database race, not a tunable product behavior.
	 */
	static final int MAX_APPEND_ATTEMPTS = 5;

	private final AuditEventRepository repository;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;

	@PersistenceContext
	private EntityManager entityManager;

	public AuditService(AuditEventRepository repository,
			PlatformTransactionManager transactionManager, Clock clock) {
		if (repository == null || transactionManager == null || clock == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.repository = repository;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Appends one event to the global chain, returning the persisted
	 * row. The timestamp defaults to the injected clock when absent
	 * and is always truncated to microseconds (PostgreSQL
	 * {@code timestamptz} precision) before canonicalization and
	 * persistence, so verify-after-persist recomputation matches.
	 */
	public AuditEvent append(UUID actorUserId, AuditEventType eventType, String resourceType,
			UUID resourceId, AuditMetadata metadata, Instant occurredAt) {
		if (eventType == null) {
			throw new IllegalArgumentException("Event type must not be null");
		}
		Instant effectiveAt = (occurredAt != null ? occurredAt : clock.instant())
				.truncatedTo(ChronoUnit.MICROS);
		String metadataJson = metadata == null ? null : metadata.toJson();
		DataIntegrityViolationException lastCollision = null;
		for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {
			try {
				return writeTransaction.execute(status -> insertOnce(actorUserId, eventType,
						resourceType, resourceId, metadataJson, effectiveAt));
			}
			catch (DataIntegrityViolationException collision) {
				lastCollision = collision;
			}
		}
		throw new AuditAppendException(
				"Audit append failed after " + MAX_APPEND_ATTEMPTS + " attempts", lastCollision);
	}

	/**
	 * Read-only, user-scoped access to the actor's events, newest
	 * first. Repository-level filtering; no Java-side filtering. The
	 * REST feed (11D) builds on this method.
	 */
	@Transactional(readOnly = true)
	public List<AuditEvent> listAuditEvents(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return repository.findByActorUser_IdOrderByOccurredAtDescIdDesc(userId);
	}

	/**
	 * Phase 11D — read-only, user-scoped feed DTOs, newest first.
	 * Repository-level ownership filtering; no Java-side filtering.
	 * Mapping runs inside this read transaction; only scalar getters
	 * are read, so the lazy actor association stays uninitialized.
	 * The REST feed builds on this method. Never writes.
	 */
	@Transactional(readOnly = true)
	public List<AuditEventResponse> listAuditEventResponses(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return repository.findByActorUser_IdOrderByOccurredAtDescIdDesc(userId).stream()
				.map(AuditEventResponse::from)
				.toList();
	}

	/**
	 * Phase 13-B — paginated audit feed (ADR-026). Same rows and
	 * newest-first order as {@link #listAuditEventResponses(UUID)},
	 * windowed by {@code page}/{@code size} (defaults 0/20, maximum
	 * 100). The Sort carries the exact feed order (occurredAt DESC, id
	 * DESC). Mapping stays inside this read transaction; only scalar
	 * getters are read so the lazy actor association stays
	 * uninitialized. The existing unbounded method stays for internal
	 * callers.
	 */
	@Transactional(readOnly = true)
	public List<AuditEventResponse> listAuditEventResponsesPaged(
			UUID userId, int page, int size) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		FeedPagination pagination = FeedPagination.of(page, size);
		Sort sort = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));
		return repository.findByActorUser_Id(userId, pagination.pageRequest(sort)).stream()
				.map(AuditEventResponse::from)
				.toList();
	}

	private AuditEvent insertOnce(UUID actorUserId, AuditEventType eventType, String resourceType,
			UUID resourceId, String metadataJson, Instant occurredAt) {
		List<AuditEvent> heads = repository.findChainHeads();
		if (heads.size() > 1) {
			throw new IllegalStateException("Multiple audit chain heads detected");
		}
		String prevHash = heads.isEmpty() ? null : heads.get(0).getEventHash();
		String eventHash = AuditChain.canonicalHash(AuditChain.CANONICAL_FORMAT_VERSION,
				eventType.name(), actorUserId, resourceType, resourceId, occurredAt,
				metadataJson, prevHash);
		User actor = actorUserId == null ? null
				: entityManager.getReference(User.class, actorUserId);
		return repository.saveAndFlush(new AuditEvent(actor, eventType, resourceType,
				resourceId, occurredAt, metadataJson, prevHash, eventHash));
	}
}
