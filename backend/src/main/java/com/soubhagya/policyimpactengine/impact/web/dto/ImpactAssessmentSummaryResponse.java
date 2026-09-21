package com.soubhagya.policyimpactengine.impact.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;

/**
 * Authenticated impact-assessment list item: the persisted summary
 * facts of one assessment, without the per-item breakdown.
 *
 * <p>No user identity, credentials, scores recomputation, or entities
 * are exposed. Bands are rendered as their enum names, matching the
 * existing policy/notification response conventions.
 *
 * <p>Mapping must execute inside the service transaction that loaded
 * the assessment: {@code newVersion}, {@code previousVersion}, and
 * {@code newVersion.policy} are lazy associations.
 */
public record ImpactAssessmentSummaryResponse(

		UUID id,
		UUID policyId,
		int versionNumber,
		int previousVersionNumber,
		int aggregateScore,
		String aggregateBand,
		int personalizationRulesVersion,
		Instant createdAt

) {

	public static ImpactAssessmentSummaryResponse from(ImpactAssessment assessment) {
		if (assessment == null) {
			throw new IllegalArgumentException("Assessment must not be null");
		}
		PolicyVersion newVersion = assessment.getNewVersion();
		return new ImpactAssessmentSummaryResponse(
				assessment.getId(),
				newVersion.getPolicy().getId(),
				newVersion.getVersionNumber(),
				assessment.getPreviousVersion().getVersionNumber(),
				assessment.getAggregateScore(),
				assessment.getAggregateBand().name(),
				assessment.getPersonalizationRulesVersion(),
				assessment.getCreatedAt());
	}

}
