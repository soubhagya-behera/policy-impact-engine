package com.soubhagya.policyimpactengine.intelligence;

import java.util.List;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;

/**
 * Phase 2N — abstraction for deterministic privacy-concept matching.
 *
 * <p>Input is one persisted change record; matching scans its canonical
 * oldText/newText. The matcher owns no repository, network, clock or
 * randomness. Deterministic: same change yields same ordered matches.
 */
public interface ConceptMatcher {

	/**
	 * Returns the deterministic concept matches for the given change.
	 *
	 * @param change persisted change record; must not be null
	 * @return ordered list of matches (by concept code), may be empty, never null
	 */
	List<ConceptMatch> match(PolicyChangeRecord change);
}
