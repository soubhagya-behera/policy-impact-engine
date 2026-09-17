package com.soubhagya.policyimpactengine.impact;

import java.util.List;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

/**
 * Phase 2Q — immutable result for one personalized assessment aggregation.
 *
 * <p>Pure value object, no Spring/DB/I/O, deterministic. Aggregation is MAX:
 * the aggregate is the maximum personalized normalized score across items;
 * empty input yields 0/NONE.
 *
 * @param items item scores in deterministic order
 * @param aggregateScore max personalized normalized, or 0 when empty
 * @param aggregateBand derived band
 * @param personalizationRulesVersion personalization rules version
 */
public record PersonalizedAssessmentResult(
		List<PersonalizedItemScore> items,
		int aggregateScore,
		ImpactBand aggregateBand,
		int personalizationRulesVersion) {

	public PersonalizedAssessmentResult {
		if (items == null) {
			throw new IllegalArgumentException("Items must not be null");
		}
		if (aggregateScore < 0 || aggregateScore > 100) {
			throw new IllegalArgumentException("Aggregate score must be between 0 and 100");
		}
		if (aggregateBand == null) {
			throw new IllegalArgumentException("Aggregate band must not be null");
		}
		if (personalizationRulesVersion < 1) {
			throw new IllegalArgumentException("Personalization rules version must be >= 1");
		}
		items = List.copyOf(items);
	}
}
