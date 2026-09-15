package com.soubhagya.policyimpactengine.diff;

/**
 * Abstraction for comparing the normalized content of two policy versions.
 *
 * <p>Answers only "what changed between Version A and Version B" — never
 * how important the change is. Callers supply the two already-normalized
 * contents; the engine performs no fetching, extraction, normalization,
 * hashing, persistence, or version lookup.
 *
 * <p>Implementations are pure and stateless: deterministic, thread-safe,
 * and independent of Spring, databases, HTTP, users, clocks, and
 * randomness.
 */
public interface PolicyDiffEngine {

	/**
	 * Compares two normalized policy contents line by line.
	 *
	 * <p>Null is defined as an empty document, consistent with the
	 * upstream normalizer mapping null to the empty string.
	 *
	 * @param oldContent previous normalized content; null means empty document
	 * @param newContent current normalized content; null means empty document
	 * @return ordered changes; empty when the contents are identical, never null
	 */
	PolicyDiffResult diff(String oldContent, String newContent);
}
