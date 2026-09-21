package com.soubhagya.policyimpactengine.audit.domain;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * Phase 11A — immutable audit record in the single global SHA-256
 * chain (see DECISIONS.md ADR-022/ADR-023).
 *
 * <p>Append-only like {@code ImpactAssessment} and
 * {@code Recommendation}: never edited or deleted, no setters, no
 * update/delete API. The actor is the owning {@link User} through a
 * lazy association (nullable: reserved for future system actors);
 * resource references stay polymorphic UUIDs with no entity
 * relationships. Chain linkage ({@code prevHash} → predecessor
 * {@code eventHash}, NULL only for genesis) is computed by
 * {@code AuditService} through {@link AuditChain} and snapshotted
 * here; this entity never hashes.
 */
@Entity
@Table(name = "audit_event", uniqueConstraints = {
		@UniqueConstraint(name = "uq_audit_event_prev_hash", columnNames = "prev_hash"),
		@UniqueConstraint(name = "uq_audit_event_hash", columnNames = "event_hash") })
@Getter
public class AuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "actor_user_id", nullable = true, updatable = false)
	private User actorUser;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, updatable = false, length = 32)
	private AuditEventType eventType;

	@Column(name = "resource_type", nullable = true, updatable = false, length = 32)
	private String resourceType;

	@Column(name = "resource_id", nullable = true, updatable = false)
	private UUID resourceId;

	@Column(name = "metadata", nullable = true, updatable = false, columnDefinition = "TEXT")
	private String metadata;

	@Column(name = "prev_hash", nullable = true, updatable = false, length = 64)
	private String prevHash;

	@Column(name = "event_hash", nullable = false, updatable = false, length = 64)
	private String eventHash;

	protected AuditEvent() {
		// Required by JPA.
	}

	public AuditEvent(User actorUser, AuditEventType eventType, String resourceType,
			UUID resourceId, Instant occurredAt, String metadata, String prevHash,
			String eventHash) {
		if (eventType == null) {
			throw new IllegalArgumentException("Event type must not be null");
		}
		if (occurredAt == null) {
			throw new IllegalArgumentException("Occurred at must not be null");
		}
		if (resourceType != null && resourceType.isBlank()) {
			throw new IllegalArgumentException("Resource type must not be blank");
		}
		if (!isHex64(eventHash)) {
			throw new IllegalArgumentException("Event hash must be 64 lowercase hex characters");
		}
		if (prevHash != null && !isHex64(prevHash)) {
			throw new IllegalArgumentException("Prev hash must be 64 lowercase hex characters");
		}
		this.actorUser = actorUser;
		this.eventType = eventType;
		this.resourceType = resourceType == null ? null : resourceType.trim();
		this.resourceId = resourceId;
		this.occurredAt = occurredAt;
		this.metadata = metadata;
		this.prevHash = prevHash;
		this.eventHash = eventHash;
	}

	private static boolean isHex64(String value) {
		if (value == null || value.length() != 64) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
				return false;
			}
		}
		return true;
	}
}
