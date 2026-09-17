package com.soubhagya.policyimpactengine.impact;

import java.math.BigDecimal;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

/**
 * Phase 2Q — immutable result for one personalized item score.
 *
 * <p>Pure value object, no Spring/DB/I/O, deterministic.
 *
 * @param personalizedBase base score after sensitivity scaling (scale 2)
 * @param personalizedNormalized min(100, round(personalizedBase x 10))
 * @param personalizedBand derived band
 */
public record PersonalizedItemScore(
		BigDecimal personalizedBase,
		int personalizedNormalized,
		ImpactBand personalizedBand) {

	public PersonalizedItemScore {
		if (personalizedBase == null || personalizedBase.doubleValue() < 0) {
			throw new IllegalArgumentException("Personalized base must be >= 0");
		}
		if (personalizedNormalized < 0 || personalizedNormalized > 100) {
			throw new IllegalArgumentException("Personalized normalized must be between 0 and 100");
		}
		if (personalizedBand == null) {
			throw new IllegalArgumentException("Personalized band must not be null");
		}
	}
}
