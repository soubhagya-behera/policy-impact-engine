package com.soubhagya.policyimpactengine.diff;

/**
 * Abstraction for computing a near-duplicate similarity fingerprint over
 * already-normalized policy text.
 *
 * <p>Answers only "how similar are two canonical texts?" — never "are they
 * exactly identical?". Exact identity remains the job of the SHA-256 content
 * hash ({@code PolicyContentHasher}), which stays authoritative for
 * unchanged detection. A SimHash fingerprint is an additional similarity
 * signal: near-identical texts tend to produce fingerprints with a small
 * Hamming distance, while the SHA-256 digests of the same two texts are
 * unrelated.
 *
 * <p>Callers supply canonical normalized text; the implementation performs
 * tokenization only and never normalizes raw input itself.
 *
 * <p>Implementations are pure and stateless: deterministic, thread-safe,
 * and independent of Spring, databases, HTTP, users, clocks, and
 * randomness.
 */
public interface PolicySimHash {

	/**
	 * Computes a deterministic 64-bit SimHash fingerprint of the given
	 * canonical normalized text.
	 *
	 * @param normalizedText canonical normalized policy text; null means an
	 *        empty document
	 * @return 64-bit fingerprint; same input always yields the same output
	 */
	long fingerprint(String normalizedText);
}
