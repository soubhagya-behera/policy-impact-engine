package com.soubhagya.policyimpactengine.impact;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentSummaryResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactSummaryResponse;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;

/**
 * Phase 14-B/4 — read-only impact summary for the authenticated REST
 * surface.
 *
 * <p>Every value comes from a user-scoped database aggregate (COUNT,
 * GROUP BY, MAX) or the existing newest-first single-row assessment
 * lookup; assessment and recommendation entities are never bulk
 * loaded. The actionable count excludes the assessment-level
 * NONE_REQUIRED closure exactly as the recommendation model defines
 * it. Nothing is created, recomputed, or mutated here. The caller's
 * user id always comes from the authenticated principal via the
 * controller; this service never touches the security context.
 */
@Service
public class ImpactSummaryReadService {

	private final ImpactAssessmentRepository assessmentRepository;
	private final RecommendationRepository recommendationRepository;

	public ImpactSummaryReadService(ImpactAssessmentRepository assessmentRepository,
			RecommendationRepository recommendationRepository) {
		if (assessmentRepository == null) {
			throw new IllegalArgumentException("ImpactAssessmentRepository must not be null");
		}
		if (recommendationRepository == null) {
			throw new IllegalArgumentException("RecommendationRepository must not be null");
		}
		this.assessmentRepository = assessmentRepository;
		this.recommendationRepository = recommendationRepository;
	}

	/**
	 * Returns the user's persisted-facts summary. An empty user
	 * yields zero counts, an empty band list, a zero maximum, and a
	 * {@code null} latest assessment.
	 */
	@Transactional(readOnly = true)
	public ImpactSummaryResponse getSummary(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		long total = assessmentRepository.countByUser_Id(userId);
		List<ImpactSummaryResponse.BandCount> byBand = assessmentRepository
				.countByBandForUser(userId).stream()
				.map(row -> new ImpactSummaryResponse.BandCount(
						((ImpactBand) row[0]).name(), (Long) row[1]))
				.sorted(Comparator.comparing(ImpactSummaryResponse.BandCount::band))
				.toList();
		int max = assessmentRepository.maxAggregateScoreForUser(userId);
		long actionable = recommendationRepository
				.countByAssessment_User_IdAndActionKindNot(
						userId, RecommendationActionKind.NONE_REQUIRED);
		ImpactAssessmentSummaryResponse latest = assessmentRepository
				.findFirstByUser_IdOrderByCreatedAtDescIdDesc(userId)
				.map(ImpactAssessmentSummaryResponse::from)
				.orElse(null);
		return new ImpactSummaryResponse(total, byBand, max, actionable, latest);
	}
}
