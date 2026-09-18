package com.soubhagya.policyimpactengine.recommendation;

/**
 * Phase 2R — pure recommendation engine.
 *
 * <p>No Spring, DB, HTTP, clock, randomness, LLM. Deterministic for the same
 * input; repeated execution produces identical results.
 */
public interface RecommendationEngine {

	/**
	 * Evaluates the frozen rules over the input and returns the ranked,
	 * deduplicated recommendation set.
	 *
	 * @param input personalized concept items plus the assessment aggregate;
	 *        must not be null
	 * @return deterministic result stamped with the frozen rules version
	 */
	RecommendationResult recommend(RecommendationInput input);
}
