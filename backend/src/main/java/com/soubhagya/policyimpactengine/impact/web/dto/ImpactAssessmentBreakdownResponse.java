package com.soubhagya.policyimpactengine.impact.web.dto;

import java.util.UUID;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;

/**
 * One persisted breakdown row of an impact assessment: the snapshot
 * that makes the deterministic personalization explainable.
 *
 * <p>All values are read from the persisted row (and its EAGER
 * {@code changeImpact} for the system rules version). Nothing is
 * recomputed here; there is deliberately no second scoring
 * algorithm in the read path.
 */
public record ImpactAssessmentBreakdownResponse(

		UUID changeImpactId,
		String conceptCode,
		String changeType,
		int systemNormalized,
		String systemBand,
		int systemRulesVersion,
		int effectiveSensitivity,
		int personalizedNormalized,
		String personalizedBand,
		int personalizationRulesVersion

) {

	public static ImpactAssessmentBreakdownResponse from(ImpactAssessmentBreakdown breakdown) {
		if (breakdown == null) {
			throw new IllegalArgumentException("Breakdown must not be null");
		}
		return new ImpactAssessmentBreakdownResponse(
				breakdown.getChangeImpact().getId(),
				breakdown.getConceptCode(),
				breakdown.getChangeType(),
				breakdown.getSystemNormalized(),
				breakdown.getSystemBand().name(),
				breakdown.getChangeImpact().getRulesVersion(),
				breakdown.getEffectiveSensitivity(),
				breakdown.getPersonalizedNormalized(),
				breakdown.getPersonalizedBand().name(),
				breakdown.getPersonalizationRulesVersion());
	}

}
