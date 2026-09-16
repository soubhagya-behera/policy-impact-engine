package com.soubhagya.policyimpactengine.impact;

import java.math.BigDecimal;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

/**
 * Phase 2O — immutable DTO for a scored impact (no JPA entity exposure).
 */
public record ChangeImpactResult(
		String conceptCode,
		String changeType,
		int conceptWeight,
		BigDecimal changeTypeMultiplier,
		BigDecimal baseScore,
		int normalizedScore,
		ImpactBand impactBand,
		int rulesVersion) {

	public ChangeImpactResult {
		if (conceptCode == null || conceptCode.isBlank()) {
			throw new IllegalArgumentException("Concept code must not be blank");
		}
		if (changeType == null || changeType.isBlank()) {
			throw new IllegalArgumentException("Change type must not be blank");
		}
		if (conceptWeight < 0) {
			throw new IllegalArgumentException("Concept weight must be >= 0");
		}
		if (changeTypeMultiplier == null) {
			throw new IllegalArgumentException("Multiplier must not be null");
		}
		if (baseScore == null) {
			throw new IllegalArgumentException("Base score must not be null");
		}
		if (impactBand == null) {
			throw new IllegalArgumentException("Impact band must not be null");
		}
		if (rulesVersion < 1) {
			throw new IllegalArgumentException("Rules version must be >= 1");
		}
	}
}
