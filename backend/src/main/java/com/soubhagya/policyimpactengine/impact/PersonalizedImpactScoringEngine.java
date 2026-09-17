package com.soubhagya.policyimpactengine.impact;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 2Q — pure personalization engine.
 *
 * <p>No Spring, DB, HTTP, clock, randomness, LLM.
 */
public interface PersonalizedImpactScoringEngine {

	/**
	 * Scores one system base score with one effective sensitivity.
	 *
	 * @param systemBase canonical ChangeImpact base score; must not be null, must be &gt;= 0
	 * @param effectiveSensitivity effective sensitivity in [0,5]
	 * @return deterministic personalized item score
	 */
	PersonalizedItemScore scoreItem(BigDecimal systemBase, int effectiveSensitivity);

	/**
	 * Scores all items and aggregates with MAX.
	 *
	 * @param systemBases system base scores in deterministic order; must not be null
	 * @param effectiveSensitivities matching effective sensitivities in [0,5]; must not be null, same size
	 * @return deterministic assessment result
	 */
	PersonalizedAssessmentResult scoreAll(List<BigDecimal> systemBases, List<Integer> effectiveSensitivities);
}
