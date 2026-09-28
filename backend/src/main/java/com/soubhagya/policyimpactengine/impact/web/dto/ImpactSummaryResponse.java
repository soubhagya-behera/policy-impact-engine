package com.soubhagya.policyimpactengine.impact.web.dto;

import java.util.List;

/**
 * Phase 14-B/4 — persisted-facts impact summary for one authenticated
 * user: assessment totals grouped by band, the maximum persisted
 * aggregate score, the actionable recommendation count, and the
 * newest assessment in the existing summary shape ({@code null} when
 * the user has no assessments).
 *
 * <p>Every value is read from persisted rows; nothing is recomputed,
 * trended, or rolled up per policy. Bands render as enum names,
 * matching the existing assessment response conventions.
 */
public record ImpactSummaryResponse(

		long totalAssessments,
		List<BandCount> assessmentsByBand,
		int maxAggregateScore,
		long actionableRecommendations,
		ImpactAssessmentSummaryResponse latest

) {

	/**
	 * One persisted per-band assessment count. Ordered by band name
	 * for determinism.
	 */
	public record BandCount(

			String band,
			long count

	) {
	}

	public ImpactSummaryResponse {
		assessmentsByBand = List.copyOf(assessmentsByBand);
	}

}
