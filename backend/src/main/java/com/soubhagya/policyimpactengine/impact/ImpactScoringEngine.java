package com.soubhagya.policyimpactengine.impact;

import java.util.List;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.impact.domain.ImpactScore;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;

/**
 * Phase 2O — pure scoring engine for system-level impact.
 *
 * <p>No Spring, DB, HTTP, clock, randomness, LLM.
 */
public interface ImpactScoringEngine {

	/**
	 * Scores one mateched concept.
	 *
	 * @param change the persisted policy change
	 * @param match the concept match with evidence
	 * @return deterministic score snapshot
	 */
	ImpactScore score(PolicyChangeRecord change, ChangeConceptMatch match);

	/**
	 * Scores all matches for a set of changes.
	 */
	List<ImpactScore> scoreAll(List<PolicyChangeRecord> changes, List<ChangeConceptMatch> matches);
}
