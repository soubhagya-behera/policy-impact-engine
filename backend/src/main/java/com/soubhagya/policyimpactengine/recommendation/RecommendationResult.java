package com.soubhagya.policyimpactengine.recommendation;

import java.util.List;

/**
 * Phase 2R — immutable recommendation engine result.
 *
 * <p>Pure value object, no Spring/DB/I/O, deterministic. Outcomes are ranked
 * by personalized score (descending), then rule order (ascending), then
 * concept code (ascending, the final deterministic tie-break).
 *
 * @param recommendations ranked outcomes; may be empty only for a degenerate
 *        assessment (aggregate band MEDIUM+ with no matching concept item)
 * @param recommendationRulesVersion frozen rules version that produced it
 */
public record RecommendationResult(
		List<RecommendationOutcome> recommendations,
		int recommendationRulesVersion) {

	public RecommendationResult {
		if (recommendations == null) {
			throw new IllegalArgumentException("Recommendations must not be null");
		}
		if (recommendationRulesVersion < 1) {
			throw new IllegalArgumentException("Recommendation rules version must be >= 1");
		}
		recommendations = List.copyOf(recommendations);
	}
}
