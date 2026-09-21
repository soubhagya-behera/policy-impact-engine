package com.soubhagya.policyimpactengine.recommendation.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;

/**
 * Authenticated recommendation detail: the persisted recommendation
 * facts plus the minimal assessment context the row is naturally
 * attached to (policy and version navigation), so a client can move
 * from a notification to the underlying assessment and policy
 * version using existing IDs.
 *
 * <p>No domain state is duplicated beyond these navigation IDs.
 * Mapping must execute inside the service transaction that loaded
 * the recommendation: {@code assessment}, {@code newVersion}, and
 * {@code newVersion.policy} are lazy associations.
 */
public record RecommendationDetailResponse(

		UUID id,
		UUID assessmentId,
		UUID policyId,
		int versionNumber,
		String ruleId,
		int ruleOrder,
		String actionKind,
		String conceptCode,
		int personalizedNormalized,
		String personalizedBand,
		int recommendationRulesVersion,
		Instant createdAt

) {

	public static RecommendationDetailResponse from(Recommendation recommendation) {
		if (recommendation == null) {
			throw new IllegalArgumentException("Recommendation must not be null");
		}
		ImpactAssessment assessment = recommendation.getAssessment();
		return new RecommendationDetailResponse(
				recommendation.getId(),
				assessment.getId(),
				assessment.getNewVersion().getPolicy().getId(),
				assessment.getNewVersion().getVersionNumber(),
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
