package com.soubhagya.policyimpactengine.policy.web.dto;

import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentDetailResponse;

/**
 * Phase 14-B/3 — assessment for one owned change.
 *
 * <p>Carries the change context in the existing
 * {@link ChangeRecordResponse} shape plus the persisted assessment
 * detail whose breakdowns are filtered to the requested change only.
 * Assessment fields and semantics match the existing assessment-detail
 * endpoint exactly; nothing is recomputed.
 */
public record ChangeAssessmentResponse(

		ChangeRecordResponse change,
		ImpactAssessmentDetailResponse assessment

) {
}
