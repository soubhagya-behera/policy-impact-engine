package com.soubhagya.policyimpactengine.impact.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;

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
 * Phase 2O — immutable system-level impact for one ChangeConceptMatch.
 *
 * <p>Append-only, one row per match, snapshots all inputs for explainability:
 * concept code/weight, change type/multiplier, base and normalized scores,
 * band and rules version. No user sensitivity, no section criticality yet
 * (criticality=1). No setters, no update/delete.
 */
@Entity
@Table(name = "change_impact", uniqueConstraints = @UniqueConstraint(name = "uq_change_impact_match", columnNames = "match_id"))
@Getter
public class ChangeImpact {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "match_id", nullable = false, updatable = false)
	private ChangeConceptMatch match;

	@Column(name = "concept_code", nullable = false, updatable = false, length = 64)
	private String conceptCode;

	@Column(name = "change_type", nullable = false, updatable = false, length = 16)
	private String changeType;

	@Column(name = "concept_weight", nullable = false, updatable = false)
	private int conceptWeight;

	@Column(name = "change_type_multiplier", nullable = false, updatable = false, precision = 3, scale = 2)
	private BigDecimal changeTypeMultiplier;

	@Column(name = "base_score", nullable = false, updatable = false, precision = 8, scale = 2)
	private BigDecimal baseScore;

	@Column(name = "normalized_score", nullable = false, updatable = false)
	private int normalizedScore;

	@Enumerated(EnumType.STRING)
	@Column(name = "impact_band", nullable = false, updatable = false, length = 16)
	private ImpactBand impactBand;

	@Column(name = "rules_version", nullable = false, updatable = false)
	private int rulesVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ChangeImpact() {
		// Required by JPA.
	}

	public ChangeImpact(ChangeConceptMatch match, String conceptCode, String changeType, int conceptWeight,
			BigDecimal changeTypeMultiplier, BigDecimal baseScore, int normalizedScore, ImpactBand impactBand,
			int rulesVersion) {
		if (match == null) {
			throw new IllegalArgumentException("Match must not be null");
		}
		if (conceptCode == null || conceptCode.isBlank()) {
			throw new IllegalArgumentException("Concept code must not be blank");
		}
		if (changeType == null || changeType.isBlank()) {
			throw new IllegalArgumentException("Change type must not be blank");
		}
		if (conceptWeight < 0) {
			throw new IllegalArgumentException("Concept weight must be >= 0");
		}
		if (changeTypeMultiplier == null || changeTypeMultiplier.doubleValue() < 0) {
			throw new IllegalArgumentException("Multiplier must be >= 0");
		}
		if (baseScore == null || baseScore.doubleValue() < 0) {
			throw new IllegalArgumentException("Base score must be >= 0");
		}
		if (normalizedScore < 0 || normalizedScore > 100) {
			throw new IllegalArgumentException("Normalized score must be between 0 and 100");
		}
		if (impactBand == null) {
			throw new IllegalArgumentException("Impact band must not be null");
		}
		if (rulesVersion < 1) {
			throw new IllegalArgumentException("Rules version must be >= 1");
		}
		this.match = match;
		this.conceptCode = conceptCode.trim();
		this.changeType = changeType.trim();
		this.conceptWeight = conceptWeight;
		this.changeTypeMultiplier = changeTypeMultiplier;
		this.baseScore = baseScore;
		this.normalizedScore = normalizedScore;
		this.impactBand = impactBand;
		this.rulesVersion = rulesVersion;
	}
}
