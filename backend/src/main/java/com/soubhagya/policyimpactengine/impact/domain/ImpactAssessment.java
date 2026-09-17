package com.soubhagya.policyimpactengine.impact.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
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
 * Phase 2Q — immutable user-specific assessment of one policy version
 * transition (previous version to new version).
 *
 * <p>Append-only like {@link ChangeImpact}: never edited or deleted.
 * No setters, no update/delete API. Identity is (user, new version);
 * a repeat request returns the existing row. Empty-impact transitions
 * persist 0/NONE with zero breakdown rows.
 */
@Entity
@Table(name = "impact_assessment", uniqueConstraints = @UniqueConstraint(name = "uq_assessment_user_new_version", columnNames = {
		"user_id", "new_version_id" }))
@Getter
public class ImpactAssessment {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "new_version_id", nullable = false, updatable = false)
	private PolicyVersion newVersion;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "previous_version_id", nullable = false, updatable = false)
	private PolicyVersion previousVersion;

	@Column(name = "aggregate_score", nullable = false, updatable = false)
	private int aggregateScore;

	@Enumerated(EnumType.STRING)
	@Column(name = "aggregate_band", nullable = false, updatable = false, length = 16)
	private ImpactBand aggregateBand;

	@Column(name = "personalization_rules_version", nullable = false, updatable = false)
	private int personalizationRulesVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ImpactAssessment() {
		// Required by JPA.
	}

	public ImpactAssessment(User user, PolicyVersion newVersion, PolicyVersion previousVersion,
			int aggregateScore, ImpactBand aggregateBand, int personalizationRulesVersion) {
		if (user == null) {
			throw new IllegalArgumentException("User must not be null");
		}
		if (newVersion == null) {
			throw new IllegalArgumentException("New version must not be null");
		}
		if (previousVersion == null) {
			throw new IllegalArgumentException("Previous version must not be null");
		}
		if (aggregateScore < 0 || aggregateScore > 100) {
			throw new IllegalArgumentException("Aggregate score must be between 0 and 100");
		}
		if (aggregateBand == null) {
			throw new IllegalArgumentException("Aggregate band must not be null");
		}
		if (personalizationRulesVersion < 1) {
			throw new IllegalArgumentException("Personalization rules version must be >= 1");
		}
		this.user = user;
		this.newVersion = newVersion;
		this.previousVersion = previousVersion;
		this.aggregateScore = aggregateScore;
		this.aggregateBand = aggregateBand;
		this.personalizationRulesVersion = personalizationRulesVersion;
	}
}
