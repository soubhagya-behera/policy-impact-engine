package com.soubhagya.policyimpactengine.diff;

/**
 * Phase 2M — immutable numerical similarity signal between two persisted
 * normalized contents for a {@code NEW_VERSION} observation.
 *
 * <p>Computed from the 64-bit SimHash fingerprints of the previous and new
 * normalized canonical texts via {@link SimHashDistance}. Report raw numbers
 * only: the previous and new fingerprints, their Hamming distance, and the
 * linear similarity {@code 1 - distance/64}. No threshold, no classification,
 * no semantic interpretation is attached.
 *
 * <p>Pure value object: no Spring, no persistence, no I/O, no clock, no
 * randomness. Deterministic: same inputs always yield the same four fields.
 *
 * @param previousHash fingerprint of the previous version's normalized content
 * @param newHash fingerprint of the new version's normalized content
 * @param hammingDistance differing bit positions between the two fingerprints,
 *        between 0 and 64 inclusive, as defined by
 *        {@link SimHashDistance#hammingDistance(long, long)}
 * @param similarity linear similarity in {@code [0.0, 1.0]} as defined by
 *        {@link SimHashDistance#similarity(long, long)}
 */
public record SimHashSimilarity(
		long previousHash,
		long newHash,
		int hammingDistance,
		double similarity) {

	public SimHashSimilarity {
		if (hammingDistance < 0 || hammingDistance > SimHashDistance.FINGERPRINT_BITS) {
			throw new IllegalArgumentException(
					"Hamming distance must be between 0 and " + SimHashDistance.FINGERPRINT_BITS);
		}
		if (Double.isNaN(similarity) || similarity < 0.0 || similarity > 1.0) {
			throw new IllegalArgumentException("Similarity must be between 0.0 and 1.0");
		}
		// Explicit cross-check: similarity must equal the linear formula
		// for the given distance, within floating-point tolerance.
		double expected = 1.0 - (hammingDistance / (double) SimHashDistance.FINGERPRINT_BITS);
		if (Math.abs(similarity - expected) > 1e-12) {
			throw new IllegalArgumentException(
					"Similarity " + similarity + " inconsistent with hammingDistance " + hammingDistance
							+ " (expected " + expected + ")");
		}
	}
}
