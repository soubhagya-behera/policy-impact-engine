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

	/**
	 * User-scoped identity lookup for the authenticated read API.
	 * A foreign id behaves as not-found (callers map the empty case
	 * to 404 and never reveal whether the row exists).
	 */
	Optional<ImpactAssessment> findByIdAndUser_Id(UUID id, UUID userId);

	/**
	 * User-scoped listing for the authenticated read API, newest
	 * first with the id as the final tie-break so the order is
	 * deterministic even when two rows share a timestamp.
	 */
	List<ImpactAssessment> findByUser_IdOrderByCreatedAtDescIdDesc(UUID userId);
}
