package com.soubhagya.policyimpactengine.impact.domain;

import java.math.BigDecimal;

/**
 * Phase 2O — immutable value object for a deterministic impact score.
 *
 * <p>Pure value object, no Spring/DB/I/O, deterministic.
 *
 * @param conceptCode code snapshot of the concept (e.g. LOCATION)
 * @param changeType change type snapshot (ADDED/REMOVED/MODIFIED)
 * @param conceptWeight weight snapshot from PrivacyConcept.default_weight
 * @param changeTypeMultiplier multiplier per change type
 * @param baseScore conceptWeight × multiplier
 * @param normalizedScore min(100, round(baseScore×10))
 * @param impactBand derived band
 * @param rulesVersion rules version (1 for Phase 2O)
 */
public record ImpactScore(
		String conceptCode,
		String changeType,
		int conceptWeight,
		BigDecimal changeTypeMultiplier,
		BigDecimal baseScore,
		int normalizedScore,
		ImpactBand impactBand,
		int rulesVersion) {

	public ImpactScore {
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
	}
}
