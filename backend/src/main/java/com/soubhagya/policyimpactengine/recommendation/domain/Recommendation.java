package com.soubhagya.policyimpactengine.recommendation.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

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
 * Phase 2R — immutable recommendation attached to an {@link ImpactAssessment}.
 *
 * <p>Append-only: never edited or deleted. The current/pending recommendation
 * set for a user is the set attached to the user's latest assessment — a
 * deliberate Phase 2R interpretation of the older "replace pending set"
 * wording (see DECISIONS.md). No setters, no update/delete API.
 *
 * <p>Ownership is derived through {@code assessment_id} →
 * {@code impact_assessment.user_id}. The row stores no duplicated
 * {@code user_id} (V3/V4/V7 no-duplicated-owner-FK convention).
 *
 * <p>The row snapshots the rule that produced it (id + declared order), the
 * action kind, the concept code ({@code null} only for the assessment-level
 * NONE_REQUIRED closure row), and the triggering personalized score/band (the
 * assessment aggregate score/band for the closure row) for full
 * traceability.
 */
@Entity
@Table(name = "recommendation", uniqueConstraints = @UniqueConstraint(name = "uq_recommendation_action_concept", columnNames = {
		"assessment_id", "action_kind", "concept_code" }))
@Getter
public class Recommendation {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "assessment_id", nullable = false, updatable = false)
	private ImpactAssessment assessment;

	@Column(name = "rule_id", nullable = false, updatable = false, length = 64)
	private String ruleId;

	@Column(name = "rule_order", nullable = false, updatable = false)
	private int ruleOrder;

	@Enumerated(EnumType.STRING)
	@Column(name = "action_kind", nullable = false, updatable = false, length = 64)
	private RecommendationActionKind actionKind;

	@Column(name = "concept_code", nullable = true, updatable = false, length = 64)
	private String conceptCode;

	@Column(name = "personalized_normalized", nullable = false, updatable = false)
	private int personalizedNormalized;

	@Enumerated(EnumType.STRING)
	@Column(name = "personalized_band", nullable = false, updatable = false, length = 16)
	private ImpactBand personalizedBand;

	@Column(name = "recommendation_rules_version", nullable = false, updatable = false)
	private int recommendationRulesVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Recommendation() {
		// Required by JPA.
	}

	public Recommendation(ImpactAssessment assessment, String ruleId, int ruleOrder,
			RecommendationActionKind actionKind, String conceptCode, int personalizedNormalized,
			ImpactBand personalizedBand, int recommendationRulesVersion) {
		if (assessment == null) {
			throw new IllegalArgumentException("Assessment must not be null");
		}
		if (ruleId == null || ruleId.isBlank()) {
			throw new IllegalArgumentException("Rule id must not be blank");
		}
		if (ruleOrder < 1) {
			throw new IllegalArgumentException("Rule order must be >= 1");
		}
		if (actionKind == null) {
			throw new IllegalArgumentException("Action kind must not be null");
		}
		if (actionKind == RecommendationActionKind.NONE_REQUIRED && conceptCode != null) {
			throw new IllegalArgumentException("NONE_REQUIRED recommendation must not carry a concept code");
		}
		if (actionKind != RecommendationActionKind.NONE_REQUIRED
				&& (conceptCode == null || conceptCode.isBlank())) {
			throw new IllegalArgumentException("Concept-level recommendation requires a concept code");
		}
		if (personalizedNormalized < 0 || personalizedNormalized > 100) {
			throw new IllegalArgumentException("Personalized normalized must be between 0 and 100");
		}
		if (personalizedBand == null) {
			throw new IllegalArgumentException("Personalized band must not be null");
		}
		if (recommendationRulesVersion < 1) {
			throw new IllegalArgumentException("Recommendation rules version must be >= 1");
		}
		this.assessment = assessment;
		this.ruleId = ruleId.trim();
		this.ruleOrder = ruleOrder;
		this.actionKind = actionKind;
		this.conceptCode = conceptCode == null ? null : conceptCode.trim();
		this.personalizedNormalized = personalizedNormalized;
		this.personalizedBand = personalizedBand;
		this.recommendationRulesVersion = recommendationRulesVersion;
	}
}
