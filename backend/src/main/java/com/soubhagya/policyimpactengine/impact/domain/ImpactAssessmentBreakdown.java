package com.soubhagya.policyimpactengine.impact.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * Phase 2Q — immutable per-ChangeImpact breakdown of an
 * {@link ImpactAssessment}.
 *
 * <p>Append-only: never edited or deleted. Snapshots the system score/band,
 * the resolved effective sensitivity, and the personalized score/band so the
 * assessment remains explainable even if preferences change later. Full
 * traceability runs breakdown to ChangeImpact to ChangeConceptMatch to
 * PolicyChangeRecord to PolicyVersion.
 */
@Entity
@Table(name = "impact_assessment_breakdown", uniqueConstraints = @UniqueConstraint(name = "uq_breakdown_assessment_impact", columnNames = {
		"assessment_id", "change_impact_id" }))
@Getter
public class ImpactAssessmentBreakdown {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "assessment_id", nullable = false, updatable = false)
	private ImpactAssessment assessment;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "change_impact_id", nullable = false, updatable = false)
	private ChangeImpact changeImpact;

	@Column(name = "concept_code", nullable = false, updatable = false, length = 64)
	private String conceptCode;

	@Column(name = "change_type", nullable = false, updatable = false, length = 16)
	private String changeType;

	@Column(name = "system_normalized", nullable = false, updatable = false)
	private int systemNormalized;

	@Enumerated(EnumType.STRING)
	@Column(name = "system_band", nullable = false, updatable = false, length = 16)
	private ImpactBand systemBand;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "effective_sensitivity", nullable = false, updatable = false)
	private int effectiveSensitivity;

	@Column(name = "personalized_normalized", nullable = false, updatable = false)
	private int personalizedNormalized;

	@Enumerated(EnumType.STRING)
	@Column(name = "personalized_band", nullable = false, updatable = false, length = 16)
	private ImpactBand personalizedBand;

	@Column(name = "personalization_rules_version", nullable = false, updatable = false)
	private int personalizationRulesVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ImpactAssessmentBreakdown() {
		// Required by JPA.
	}

	public ImpactAssessmentBreakdown(ImpactAssessment assessment, ChangeImpact changeImpact,
			int effectiveSensitivity, int personalizedNormalized, ImpactBand personalizedBand,
			int personalizationRulesVersion) {
		if (assessment == null) {
			throw new IllegalArgumentException("Assessment must not be null");
		}
		if (changeImpact == null) {
			throw new IllegalArgumentException("Change impact must not be null");
		}
		if (effectiveSensitivity < 0 || effectiveSensitivity > 5) {
			throw new IllegalArgumentException("Effective sensitivity must be between 0 and 5");
		}
		if (personalizedNormalized < 0 || personalizedNormalized > 100) {
			throw new IllegalArgumentException("Personalized normalized must be between 0 and 100");
		}
		if (personalizedBand == null) {
			throw new IllegalArgumentException("Personalized band must not be null");
		}
		if (personalizationRulesVersion < 1) {
			throw new IllegalArgumentException("Personalization rules version must be >= 1");
		}
		this.assessment = assessment;
		this.changeImpact = changeImpact;
		this.conceptCode = changeImpact.getConceptCode();
		this.changeType = changeImpact.getChangeType();
		this.systemNormalized = changeImpact.getNormalizedScore();
		this.systemBand = changeImpact.getImpactBand();
		this.effectiveSensitivity = effectiveSensitivity;
		this.personalizedNormalized = personalizedNormalized;
		this.personalizedBand = personalizedBand;
		this.personalizationRulesVersion = personalizationRulesVersion;
	}
}
