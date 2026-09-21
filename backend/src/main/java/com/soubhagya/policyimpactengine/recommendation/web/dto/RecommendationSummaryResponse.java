package com.soubhagya.policyimpactengine.recommendation.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;

/**
 * Authenticated recommendation list item: the persisted facts of one
 * recommendation row, using the entity's real fields.
 *
 * <p>No user identity, credentials, scores recomputation, or entities
 * are exposed. Bands and action kinds are rendered as their enum
 * names, matching the existing policy/notification response
 * conventions. {@code conceptCode} is {@code null} only for the
 * assessment-level {@code NONE_REQUIRED} closure row.
 *
 * <p>Mapping must execute inside the service transaction that loaded
 * the recommendation when the assessment context is resolved (the
 * {@code assessment} association is lazy); the row's own columns are
 * safe to read anywhere.
 */
public record RecommendationSummaryResponse(

		UUID id,
		UUID assessmentId,
		String ruleId,
		int ruleOrder,
		String actionKind,
		String conceptCode,
		int personalizedNormalized,
		String personalizedBand,
		int recommendationRulesVersion,
		Instant createdAt

) {

	public static RecommendationSummaryResponse from(Recommendation recommendation) {
		if (recommendation == null) {
			throw new IllegalArgumentException("Recommendation must not be null");
		}
		return new RecommendationSummaryResponse(
				recommendation.getId(),
				recommendation.getAssessment().getId(),
				recommendation.getRuleId(),
				recommendation.getRuleOrder(),
				recommendation.getActionKind().name(),
				recommendation.getConceptCode(),
				recommendation.getPersonalizedNormalized(),
				recommendation.getPersonalizedBand().name(),
				recommendation.getRecommendationRulesVersion(),
				recommendation.getCreatedAt());
	}

}
