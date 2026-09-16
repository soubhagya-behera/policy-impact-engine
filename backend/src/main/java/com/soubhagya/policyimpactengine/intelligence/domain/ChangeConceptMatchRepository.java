package com.soubhagya.policyimpactengine.intelligence.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ChangeConceptMatch}.
 *
 * <p>Minimal operations required by Phase 2N: batch save of deterministic
 * matches inside the version-plus-changes transaction and deterministic
 * retrieval ordered by concept code.
 */
public interface ChangeConceptMatchRepository extends JpaRepository<ChangeConceptMatch, UUID> {

	/**
	 * Returns all matches for the given change, in deterministic concept-code order.
	 */
	List<ChangeConceptMatch> findByChange_IdOrderByConcept_CodeAsc(UUID changeId);

	/**
	 * Returns all matches for the given concept, ordered by change identifier.
	 */
	List<ChangeConceptMatch> findByConcept_IdOrderByChange_IdAsc(UUID conceptId);
}
