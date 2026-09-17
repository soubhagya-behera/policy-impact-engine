package com.soubhagya.policyimpactengine.user.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 2P — minimal user-scoped preference queries.
 */
public interface UserPrivacyPreferenceRepository extends JpaRepository<UserPrivacyPreference, UUID> {

	List<UserPrivacyPreference> findByUser_IdOrderByConcept_CodeAsc(UUID userId);

	Optional<UserPrivacyPreference> findByUser_IdAndConcept_Code(UUID userId, String conceptCode);

	boolean existsByUser_IdAndConcept_Id(UUID userId, UUID conceptId);

	Optional<UserPrivacyPreference> findByUser_IdAndConcept_Id(UUID userId, UUID conceptId);
}
