package com.soubhagya.policyimpactengine.impact;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

/**
 * Phase 2Q — deterministic personalized impact scorer.
 *
 * <p>Pure Java, no Spring/DB/HTTP/clock/randomness/LLM.
 *
 * <p>Formula (personalizationRulesVersion=1):
 * <pre>
 * personalizedBase = systemBase x effectiveSensitivity / 3.0 (scale 2, HALF_UP)
 * personalizedNormalized = min(100, round(personalizedBase x 10)) (HALF_UP)
 * band = ImpactBand.fromNormalizedScore(personalizedNormalized)
 * aggregate = max(personalizedNormalized), empty -&gt; 0/NONE
 * </pre>
 *
 * <p>systemBase is the canonical ChangeImpact base score (never the
 * normalized score, to avoid double rounding). Sensitivity 3 is the neutral
 * baseline (identity); 0 zeroes the contribution; 5 amplifies (capped at 100).
 */
public class DeterministicPersonalizedImpactScoringEngine implements PersonalizedImpactScoringEngine {

	public static final int PERSONALIZATION_RULES_VERSION = 1;

	private static final BigDecimal NEUTRAL_BASELINE = new BigDecimal("3.0");

	@Override
	public PersonalizedItemScore scoreItem(BigDecimal systemBase, int effectiveSensitivity) {
		if (systemBase == null) {
			throw new IllegalArgumentException("System base must not be null");
		}
		if (systemBase.doubleValue() < 0) {
			throw new IllegalArgumentException("System base must be >= 0");
		}
		if (effectiveSensitivity < 0 || effectiveSensitivity > 5) {
			throw new IllegalArgumentException("Effective sensitivity must be between 0 and 5");
		}
		BigDecimal personalizedBase = systemBase
				.multiply(BigDecimal.valueOf(effectiveSensitivity))
				.divide(NEUTRAL_BASELINE, 2, RoundingMode.HALF_UP);
		int personalizedNormalized = personalizedBase.multiply(BigDecimal.TEN)
				.setScale(0, RoundingMode.HALF_UP).intValue();
		personalizedNormalized = Math.min(100, personalizedNormalized);
		ImpactBand band = ImpactBand.fromNormalizedScore(personalizedNormalized);
		return new PersonalizedItemScore(personalizedBase, personalizedNormalized, band);
	}

	@Override
	public PersonalizedAssessmentResult scoreAll(List<BigDecimal> systemBases, List<Integer> effectiveSensitivities) {
		if (systemBases == null) {
			throw new IllegalArgumentException("System bases must not be null");
		}
		if (effectiveSensitivities == null) {
			throw new IllegalArgumentException("Effective sensitivities must not be null");
		}
		if (systemBases.size() != effectiveSensitivities.size()) {
			throw new IllegalArgumentException("System bases and sensitivities must have the same size");
		}
		List<PersonalizedItemScore> items = new ArrayList<>(systemBases.size());
		int aggregate = 0;
		for (int i = 0; i < systemBases.size(); i++) {
			PersonalizedItemScore item = scoreItem(systemBases.get(i), effectiveSensitivities.get(i));
			items.add(item);
			aggregate = Math.max(aggregate, item.personalizedNormalized());
		}
		return new PersonalizedAssessmentResult(
				List.copyOf(items), aggregate,
				ImpactBand.fromNormalizedScore(aggregate), PERSONALIZATION_RULES_VERSION);
	}
}
