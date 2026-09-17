package com.soubhagya.policyimpactengine.impact.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 2Q — persistence for {@link ImpactAssessmentBreakdown}.
 *
 * <p>Reads are scoped through the owning assessment id; callers must verify
 * assessment ownership (user scope) before reading breakdowns.
 */
public interface ImpactAssessmentBreakdownRepository extends JpaRepository<ImpactAssessmentBreakdown, UUID> {

	List<ImpactAssessmentBreakdown> findByAssessment_IdOrderByPersonalizedNormalizedDescConceptCodeAsc(
			UUID assessmentId);

	List<ImpactAssessmentBreakdown> findByAssessment_IdOrderByConceptCodeAsc(UUID assessmentId);
}
