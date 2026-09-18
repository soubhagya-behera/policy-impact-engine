package com.soubhagya.policyimpactengine.recommendation;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;

/**
 * Phase 2R — immutable single engine output: one recommendation outcome
 * before persistence.
 *
 * <p>Pure value object, no Spring/DB/I/O, deterministic.
 *
 * @param ruleId id of the rule that produced this outcome
 * @param ruleOrder declared rule order
 * @param actionKind recommended action kind
 * @param conceptCode concept code, or {@code null} only for the
 *        assessment-level NONE_REQUIRED closure outcome
 * @param personalizedNormalized score snapshot: the triggering item's
 *        personalized normalized score, or the assessment aggregate score for
 *        the NONE_REQUIRED closure outcome
 * @param personalizedBand band snapshot matching the score snapshot
 */
public record RecommendationOutcome(
		String ruleId,
		int ruleOrder,
		RecommendationActionKind actionKind,
		String conceptCode,
		int personalizedNormalized,
		ImpactBand personalizedBand) {

	public RecommendationOutcome {
		if (ruleId == null || ruleId.isBlank()) {
			throw new IllegalArgumentException("Rule id must not be blank");
		}
		if (ruleOrder < 1) {
			throw new IllegalArgumentException("Rule order must be >= 1");
		}
		if (actionKind == null) {
			throw new IllegalArgumentException("Action kind must not be null");
		}
		if (actionKind == RecommendationActionKind.NONE_REQUIRED && conceptCode != null) {
			throw new IllegalArgumentException("NONE_REQUIRED outcome must not carry a concept code");
		}
		if (actionKind != RecommendationActionKind.NONE_REQUIRED
				&& (conceptCode == null || conceptCode.isBlank())) {
			throw new IllegalArgumentException("Concept-level outcome requires a concept code");
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
		ruleId = ruleId.trim();
		if (conceptCode != null) {
			conceptCode = conceptCode.trim();
		}
	}
}
