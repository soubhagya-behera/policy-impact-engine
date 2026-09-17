package com.soubhagya.policyimpactengine.impact.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 2Q — persistence for {@link ImpactAssessment}.
 *
 * <p>User-scoped queries only; no unrestricted listing.
 */
public interface ImpactAssessmentRepository extends JpaRepository<ImpactAssessment, UUID> {

	Optional<ImpactAssessment> findByUser_IdAndNewVersion_Id(UUID userId, UUID newVersionId);

	List<ImpactAssessment> findByUser_IdOrderByCreatedAtDesc(UUID userId);
}
