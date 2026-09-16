package com.soubhagya.policyimpactengine.impact.domain;

/**
 * Phase 2O — deterministic system-level impact band.
 *
 * <p>Thresholds are fixed and versioned as part of rulesVersion=1:
 * <ul>
 * <li>0 → NONE
 * <li>1–29 → LOW
 * <li>30–54 → MEDIUM
 * <li>55–79 → HIGH
 * <li>80–100 → CRITICAL
 * </ul>
 */
public enum ImpactBand {
	NONE,
	LOW,
	MEDIUM,
	HIGH,
	CRITICAL;

	/**
	 * Derives the band for a normalized score in [0,100].
	 */
	public static ImpactBand fromNormalizedScore(int normalizedScore) {
		if (normalizedScore < 0 || normalizedScore > 100) {
			throw new IllegalArgumentException("Normalized score must be between 0 and 100");
		}
		if (normalizedScore == 0) {
			return NONE;
		}
		if (normalizedScore <= 29) {
			return LOW;
		}
		if (normalizedScore <= 54) {
			return MEDIUM;
		}
		if (normalizedScore <= 79) {
			return HIGH;
		}
		return CRITICAL;
	}
}
