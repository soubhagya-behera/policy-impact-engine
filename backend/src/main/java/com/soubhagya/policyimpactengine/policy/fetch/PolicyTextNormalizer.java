package com.soubhagya.policyimpactengine.policy.fetch;

/**
 * Application-level abstraction for normalizing already-extracted policy
 * text into a canonical deterministic representation.
 *
 * <p>Normalization answers only "how do we canonicalize extracted text so
 * that formatting noise produces the same representation?". It performs
 * canonical formatting, never semantic transformation: no lowercasing, no
 * stemming, no stop-word removal, no punctuation removal, and no
 * rewriting of the actual policy wording. The output is suitable as the
 * stable input for later hashing and comparison slices.
 *
 * <p>Implementations are plain Java: no Spring, no database, no
 * repositories, no HTTP, no security, no user data, no clocks, and no
 * randomness.
 */
public interface PolicyTextNormalizer {

	/**
	 * Normalizes the given extracted text deterministically.
	 *
	 * @param text extracted policy text; may be null
	 * @return canonical normalized text; never null (empty string when
	 *         there is no content to normalize)
	 */
	String normalize(String text);
}
