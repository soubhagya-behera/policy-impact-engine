package com.soubhagya.policyimpactengine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Phase 2L — deterministic unit tests for 64-bit SimHash comparison.
 *
 * <p>No Spring context, no database, no network. Pure JDK arithmetic on
 * inline long literals.
 */
class SimHashDistanceTest {

	@Test
	void identicalFingerprintsHaveZeroDistance() {
		assertThat(SimHashDistance.hammingDistance(0L, 0L)).isZero();
		assertThat(SimHashDistance.hammingDistance(0x123456789ABCDEFL, 0x123456789ABCDEFL)).isZero();
		assertThat(SimHashDistance.hammingDistance(-1L, -1L)).isZero();
	}

	@Test
	void knownOneBitDifferenceHasDistanceOne() {
		assertThat(SimHashDistance.hammingDistance(0b1010L, 0b1011L)).isEqualTo(1);
		assertThat(SimHashDistance.hammingDistance(0L, 1L)).isEqualTo(1);
		assertThat(SimHashDistance.hammingDistance(0L, Long.MIN_VALUE)).isEqualTo(1);
	}

	@Test
	void knownMultiBitDifferencesHaveExpectedCounts() {
		assertThat(SimHashDistance.hammingDistance(0b1010L, 0b0101L)).isEqualTo(4);
		assertThat(SimHashDistance.hammingDistance(0xFFFFFFFFFFFFFFFFL, 0L)).isEqualTo(64);
		assertThat(SimHashDistance.hammingDistance(0xFFL, 0xF0L)).isEqualTo(4);
	}

	@Test
	void distanceIsSymmetric() {
		long first = 0x310A684E00000D43L;
		long second = 0x0410D84600088803L;

		assertThat(SimHashDistance.hammingDistance(first, second))
				.isEqualTo(SimHashDistance.hammingDistance(second, first));
	}

	@Test
	void distanceIsDeterministic() {
		long first = 0x310A684E00000D43L;
		long second = 0x0410D84600088803L;

		assertThat(SimHashDistance.hammingDistance(first, second))
				.isEqualTo(SimHashDistance.hammingDistance(first, second));
	}

	@Test
	void similarityIsOneForIdenticalFingerprints() {
		assertThat(SimHashDistance.similarity(42L, 42L)).isEqualTo(1.0);
	}

	@Test
	void similarityFollowsOneMinusDistanceOver64() {
		long left = 0b1010L;
		long right = 0b0101L;

		assertThat(SimHashDistance.similarity(left, right))
				.isEqualTo(1.0 - (4 / 64.0));
		assertThat(SimHashDistance.similarity(0xFFFFFFFFFFFFFFFFL, 0L)).isEqualTo(0.0);
		assertThat(SimHashDistance.similarity(0L, 1L)).isEqualTo(1.0 - (1 / 64.0));
	}

	@Test
	void fingerprintWidthIs64Bits() {
		assertThat(SimHashDistance.FINGERPRINT_BITS).isEqualTo(64);
	}
}
