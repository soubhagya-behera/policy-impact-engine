package com.soubhagya.policyimpactengine.notification.domain;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Phase 10A — in-app notification recording that one personalized
 * {@link ImpactAssessment} produced actionable impact for its user.
 *
 * <p>Append-only like {@link com.soubhagya.policyimpactengine.recommendation.domain.Recommendation}:
 * rows are never edited (except the single mark-read transition below) and
 * never deleted. Exactly one row exists per assessment, guarded by the
 * {@code uq_notification_assessment} UNIQUE index. No setters, no
 * update/delete API beyond {@link #markRead}.
 *
 * <p>Ownership is derived through {@code assessment_id} →
 * {@code impact_assessment.user_id}. The row stores no duplicated
 * {@code user_id} (V3/V4/V7/V8 no-duplicated-owner-FK convention).
 *
 * <p>Lifecycle mutability is explicitly sanctioned and narrow:
 * {@code read_at} moves from {@code NULL} to a timestamp exactly once.
 * A second mark-read is a no-op; {@code read_at} is never moved backwards
 * or reset, enforced here and by the schema CHECK.
 */
@Entity
@Table(name = "notification", uniqueConstraints = @UniqueConstraint(name = "uq_notification_assessment", columnNames = {
		"assessment_id" }))
@Getter
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "assessment_id", nullable = false, updatable = false)
	private ImpactAssessment assessment;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "read_at", nullable = true, updatable = true)
	private Instant readAt;

	protected Notification() {
		// Required by JPA.
	}

	public Notification(ImpactAssessment assessment, Instant createdAt) {
		if (assessment == null) {
			throw new IllegalArgumentException("Assessment must not be null");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("Created at must not be null");
		}
		this.assessment = assessment;
		this.createdAt = createdAt;
		this.readAt = null;
	}

	/**
	 * Records the user reading this notification. Idempotent: a second call
	 * is a no-op returning silently, and the timestamp is never moved
	 * backwards or reset.
	 */
	public void markRead(Instant readAt) {
		if (readAt == null) {
			throw new IllegalArgumentException("Read at must not be null");
		}
		if (this.readAt != null) {
			return;
		}
		if (readAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("Read at must not be before created at");
		}
		this.readAt = readAt;
	}
}
