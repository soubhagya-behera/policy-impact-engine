package com.soubhagya.policyimpactengine.recommendation.domain;

import java.util.List;
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
}
