package com.soubhagya.policyimpactengine.recommendation;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

/**
 * Phase 2R — immutable engine input item: one personalized concept item
 * (typically one {@code impact_assessment_breakdown} row).
 *
 * <p>Pure value object, no Spring/DB/I/O, deterministic.
 *
 * @param conceptCode concept code (proxy for "concept category")
 * @param changeType ADDED/REMOVED/MODIFIED
 * @param personalizedNormalized user-specific normalized score (0–100)
 * @param personalizedBand user-specific band derived from the score
 */
public record ConceptItemScore(
		String conceptCode,
		String changeType,
		int personalizedNormalized,
		ImpactBand personalizedBand) {

	public ConceptItemScore {
		if (conceptCode == null || conceptCode.isBlank()) {
			throw new IllegalArgumentException("Concept code must not be blank");
		}
		if (changeType == null || changeType.isBlank()) {
			throw new IllegalArgumentException("Change type must not be blank");
		}
		if (personalizedNormalized < 0 || personalizedNormalized > 100) {
			throw new IllegalArgumentException("Personalized normalized must be between 0 and 100");
		}
		if (personalizedBand == null) {
			throw new IllegalArgumentException("Personalized band must not be null");
		}
		if (personalizedBand != ImpactBand.fromNormalizedScore(personalizedNormalized)) {
			throw new IllegalArgumentException("Personalized band must match the personalized normalized score");
		}
		conceptCode = conceptCode.trim();
		changeType = changeType.trim();
	}
}
