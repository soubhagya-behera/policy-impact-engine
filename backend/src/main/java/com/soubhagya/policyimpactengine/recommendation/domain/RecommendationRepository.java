package com.soubhagya.policyimpactengine.recommendation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 2R — persistence for {@link Recommendation}.
 *
 * <p>Assessment-scoped queries only; no unrestricted listing. Ownership is
 * derived through the assessment (Recommendation → ImpactAssessment → User),
 * so callers first resolve the user's assessment via
 * {@code ImpactAssessmentService} and then read its rows here. The read order
 * mirrors the engine ranking exactly: personalized score (descending), rule
 * order (ascending), concept code (ascending, final tie-break).
 */
public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {

	List<Recommendation> findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
			UUID assessmentId);

	/**
	 * User-scoped listing for the authenticated read API. Ownership
	 * is derived through the assessment (Recommendation →
	 * ImpactAssessment → User); no duplicated {@code user_id} is
	 * stored or filtered on. Newest first with the id as the final
	 * tie-break so the order is deterministic even when two rows
	 * share a timestamp.
	 */
	List<Recommendation> findByAssessment_User_IdOrderByCreatedAtDescIdDesc(UUID userId);

	/**
	 * User-scoped identity lookup for the authenticated read API.
	 * A foreign id behaves as not-found (callers map the empty case
	 * to 404 and never reveal whether the row exists).
	 */
	Optional<Recommendation> findByIdAndAssessment_User_Id(UUID id, UUID userId);
}
