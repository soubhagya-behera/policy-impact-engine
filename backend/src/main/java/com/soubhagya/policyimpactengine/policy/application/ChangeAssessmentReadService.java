package com.soubhagya.policyimpactengine.policy.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentNotFoundException;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentBreakdownResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentDetailResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.ChangeAssessmentResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.ChangeRecordResponse;

/**
 * Phase 14-B/3 — read-only change-to-assessment query for the
 * authenticated REST surface.
 *
 * <p>The requested change is resolved owner-scoped at the repository
 * level through {@code change.newVersion.policy.owner}; a foreign or
 * unknown change behaves as not-found. The user's existing assessment
 * for the change's new version is then read — never created — and its
 * persisted breakdowns are filtered to the requested change only. No
 * scores are recomputed and no rows are written. The caller's user id
 * always comes from the authenticated principal via the controller;
 * this service never touches the security context.
 */
@Service
public class ChangeAssessmentReadService {

	private final PolicyChangeRecordRepository changeRepository;
	private final ImpactAssessmentRepository assessmentRepository;
	private final ImpactAssessmentBreakdownRepository breakdownRepository;

	public ChangeAssessmentReadService(PolicyChangeRecordRepository changeRepository,
			ImpactAssessmentRepository assessmentRepository,
			ImpactAssessmentBreakdownRepository breakdownRepository) {
		if (changeRepository == null) {
			throw new IllegalArgumentException("PolicyChangeRecordRepository must not be null");
		}
		if (assessmentRepository == null) {
			throw new IllegalArgumentException("ImpactAssessmentRepository must not be null");
		}
		if (breakdownRepository == null) {
			throw new IllegalArgumentException("ImpactAssessmentBreakdownRepository must not be null");
		}
		this.changeRepository = changeRepository;
		this.assessmentRepository = assessmentRepository;
		this.breakdownRepository = breakdownRepository;
	}

	/**
	 * Returns the change context plus the user's existing assessment
	 * detail for the change's new version, with breakdowns filtered to
	 * the requested change in the existing engine ranking order. A
	 * foreign or unknown change, or an owned change with no assessment,
	 * behaves as not-found.
	 */
	@Transactional(readOnly = true)
	public ChangeAssessmentResponse getForChange(UUID userId, UUID changeId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (changeId == null) {
			throw new IllegalArgumentException("Change id must not be null");
		}
		PolicyChangeRecord change = changeRepository
				.findByIdAndNewVersion_Policy_Owner_Id(changeId, userId)
				.orElseThrow(() -> new ImpactAssessmentNotFoundException(
						"Change " + changeId + " not found"));
		ImpactAssessment assessment = assessmentRepository
				.findByUser_IdAndNewVersion_Id(userId, change.getNewVersion().getId())
				.orElseThrow(() -> new ImpactAssessmentNotFoundException(
						"Assessment not found for change " + changeId));
		List<ImpactAssessmentBreakdownResponse> breakdowns = breakdownRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescConceptCodeAsc(
						assessment.getId())
				.stream()
				.filter(breakdown -> breakdown.getChangeImpact().getMatch().getChange().getId()
						.equals(change.getId()))
				.map(ImpactAssessmentBreakdownResponse::from)
				.toList();
		return new ChangeAssessmentResponse(
				ChangeRecordResponse.from(change),
				ImpactAssessmentDetailResponse.from(assessment, breakdowns));
	}
}
