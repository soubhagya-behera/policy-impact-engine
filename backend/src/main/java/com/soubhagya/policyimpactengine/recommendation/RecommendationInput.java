package com.soubhagya.policyimpactengine.recommendation;

import java.util.List;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

/**
 * Phase 2R — immutable recommendation engine input.
 *
 * <p>Pure value object, no Spring/DB/I/O, deterministic. Items carry the
 * user-specific {@code personalizedBand} used by concept-level rules 1–3;
 * the aggregate band is used only by the REC-NONE-REQUIRED closure rule.
 *
 * @param items personalized concept items (may be empty)
 * @param aggregateScore assessment aggregate normalized score (0–100)
 * @param aggregateBand assessment aggregate band, consistent with the score
 */
public record RecommendationInput(
		List<ConceptItemScore> items,
		int aggregateScore,
		ImpactBand aggregateBand) {

	public RecommendationInput {
		if (items == null) {
			throw new IllegalArgumentException("Items must not be null");
		}
		if (aggregateScore < 0 || aggregateScore > 100) {
			throw new IllegalArgumentException("Aggregate score must be between 0 and 100");
		}
		if (aggregateBand == null) {
			throw new IllegalArgumentException("Aggregate band must not be null");
		}
		if (aggregateBand != ImpactBand.fromNormalizedScore(aggregateScore)) {
			throw new IllegalArgumentException("Aggregate band must match the aggregate score");
		}
		items = List.copyOf(items);
	}
}
