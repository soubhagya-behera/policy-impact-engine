package com.soubhagya.policyimpactengine.intelligence;

/**
 * Phase 2N — deterministic value object produced by {@link ConceptMatcher}.
 *
 * <p>Pure value object: no Spring, no persistence, no I/O. Carries the
 * concept code, the evidence fragment (an actual substring of the source
 * change text), the pattern that fired and the match kind. Deterministic:
 * same inputs always yield same ordered list.
 *
 * @param conceptCode code of the matched privacy concept (e.g. LOCATION)
 * @param matchedFragment evidence substring taken verbatim from oldText or newText
 * @param patternId identifier of the pattern that fired (e.g. LOCATION:location)
 * @param matchKind deterministic category (e.g. KEYWORD, PHRASE)
 */
public record ConceptMatch(
		String conceptCode,
		String matchedFragment,
		String patternId,
		String matchKind) {

	public ConceptMatch {
		if (conceptCode == null || conceptCode.isBlank()) {
			throw new IllegalArgumentException("Concept code must not be blank");
		}
		if (matchedFragment == null || matchedFragment.isBlank()) {
			throw new IllegalArgumentException("Matched fragment must not be blank");
		}
		if (patternId == null || patternId.isBlank()) {
			throw new IllegalArgumentException("Pattern id must not be blank");
		}
		if (matchKind == null || matchKind.isBlank()) {
			throw new IllegalArgumentException("Match kind must not be blank");
		}
	}
}
