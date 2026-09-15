package com.soubhagya.policyimpactengine.diff;

/**
 * Deterministic utility for comparing 64-bit SimHash fingerprints.
 *
 * <p>Plain JDK-only helpers with no Spring, persistence, I/O, clock, or
 * randomness dependencies. This class exposes raw distance and similarity
 * values only; it defines no domain threshold such as "0.9 means same
 * policy" — threshold calibration, if ever needed, belongs to a later
 * evaluation/integration slice.
 */
public final class SimHashDistance {

	/** Width of the SimHash fingerprints this utility compares. */
	public static final int FINGERPRINT_BITS = 64;

	private SimHashDistance() {
		// Utility class; no instances.
	}

	/**
	 * Counts the differing bit positions between two fingerprints.
	 *
	 * @param left first fingerprint
	 * @param right second fingerprint
	 * @return number of differing bits, between 0 and 64 inclusive;
	 *         identical fingerprints yield 0
	 */
	public static int hammingDistance(long left, long right) {
		return Long.bitCount(left ^ right);
	}

	/**
	 * Converts a Hamming distance into a normalized similarity in
	 * {@code [0.0, 1.0]} via {@code 1 - (distance / 64.0)}.
	 *
	 * @param left first fingerprint
	 * @param right second fingerprint
	 * @return 1.0 for identical fingerprints, decreasing linearly with distance
	 */
	public static double similarity(long left, long right) {
		return 1.0 - (hammingDistance(left, right) / (double) FINGERPRINT_BITS);
	}
}
