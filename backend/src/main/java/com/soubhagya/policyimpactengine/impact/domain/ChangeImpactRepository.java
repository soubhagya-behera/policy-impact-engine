package com.soubhagya.policyimpactengine.impact.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 2O — persistence for {@link ChangeImpact}.
 *
 * <p>Minimal operations: batch save and deterministic retrieval.
 */
public interface ChangeImpactRepository extends JpaRepository<ChangeImpact, UUID> {

	List<ChangeImpact> findByMatch_Id(UUID matchId);

	List<ChangeImpact> findByMatch_Change_IdOrderByImpactBandDescConceptCodeAsc(UUID changeId);
}
