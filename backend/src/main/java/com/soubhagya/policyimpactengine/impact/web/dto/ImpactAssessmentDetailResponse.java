package com.soubhagya.policyimpactengine.impact.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;

/**
 * Authenticated impact-assessment detail: the persisted summary facts
 * plus the ordered breakdown rows.
 *
 * <p>Mapping must execute inside the service transaction that loaded
 * the assessment: {@code newVersion}, {@code previousVersion}, and
 * {@code newVersion.policy} are lazy associations.
 */
public record ImpactAssessmentDetailResponse(

		UUID id,
		UUID policyId,
		int versionNumber,
		int previousVersionNumber,
		int aggregateScore,
		String aggregateBand,
		int personalizationRulesVersion,
		Instant createdAt,
		List<ImpactAssessmentBreakdownResponse> breakdowns

) {

	public static ImpactAssessmentDetailResponse from(ImpactAssessment assessment,
			List<ImpactAssessmentBreakdownResponse> breakdowns) {
		if (assessment == null) {
			throw new IllegalArgumentException("Assessment must not be null");
		}
		if (breakdowns == null) {
			throw new IllegalArgumentException("Breakdowns must not be null");
		}
		PolicyVersion newVersion = assessment.getNewVersion();
		return new ImpactAssessmentDetailResponse(
				assessment.getId(),
				newVersion.getPolicy().getId(),
				newVersion.getVersionNumber(),
				assessment.getPreviousVersion().getVersionNumber(),
				assessment.getAggregateScore(),
				assessment.getAggregateBand().name(),
				assessment.getPersonalizationRulesVersion(),
				assessment.getCreatedAt(),
				List.copyOf(breakdowns));
	}

}
