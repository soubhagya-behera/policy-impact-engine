package com.soubhagya.policyimpactengine.intelligence.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link PrivacyConcept}.
 *
 * <p>Minimal operations required by Phase 2N: list the vocabulary and
 * look up a single concept by its unique code. Vocabulary rows are seeded
 * by Flyway V4 and never updated or deleted through this repository.
 */
public interface PrivacyConceptRepository extends JpaRepository<PrivacyConcept, UUID> {

	Optional<PrivacyConcept> findByCode(String code);
}
