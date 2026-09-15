package com.soubhagya.policyimpactengine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

/**
 * Phase 2L — deterministic unit tests for the 64-bit SimHash fingerprint.
 *
 * <p>No Spring context, no database, no network. Inline strings only; the
 * normalizer and SHA-256 hasher are used as plain-Java collaborators in the
 * relevant composition/distinction tests.
 */
class DefaultPolicySimHashTest {

	private final PolicySimHash simHash = new DefaultPolicySimHash();

	@Test
	void emptyInputYieldsZeroFingerprint() {
		assertThat(simHash.fingerprint("")).isZero();
		assertThat(simHash.fingerprint(null)).isZero();
		assertThat(simHash.fingerprint("!!! ... \n\n")).isZero();
	}

	@Test
	void sameNormalizedTextYieldsIdenticalFingerprint() {
		String canonical = "Privacy Policy\nWe collect your location data.";

		assertThat(simHash.fingerprint(canonical)).isEqualTo(simHash.fingerprint(canonical));
	}

	@Test
	void repeatedCalculationYieldsIdenticalFingerprint() {
		String canonical = "Privacy Policy\nWe collect your location data.\nWe retain data for 30 days.";

		long first = simHash.fingerprint(canonical);
		long second = simHash.fingerprint(canonical);
		long third = simHash.fingerprint(canonical);

		assertThat(second).isEqualTo(first);
		assertThat(third).isEqualTo(first);
	}

	@Test
	void multipleInstancesYieldIdenticalFingerprint() {
		String canonical = "Privacy Policy\nWe collect your location data.";

		assertThat(new DefaultPolicySimHash().fingerprint(canonical))
				.isEqualTo(new DefaultPolicySimHash().fingerprint(canonical));
		assertThat(new DefaultPolicySimHash().fingerprint(canonical))
				.isEqualTo(simHash.fingerprint(canonical));
	}

	@Test
	void smallTextFixtureIsDeterministicAndNonTrivial() {
		long first = simHash.fingerprint("privacy");
		long second = simHash.fingerprint("privacy");

		assertThat(second).isEqualTo(first);
		assertThat(first).isNotZero();
	}

	@Test
	void realisticPrivacyPolicyFixtureIsDeterministic() {
		String fixture = "Privacy Policy\nWe collect your location data.\n"
				+ "This policy applies to all users.\nWe retain data for 30 days.";

		long first = simHash.fingerprint(fixture);

		assertThat(simHash.fingerprint(fixture)).isEqualTo(first);
		assertThat(first).isNotZero();
		assertThat(new DefaultPolicySimHash().fingerprint(fixture)).isEqualTo(first);
	}

	@Test
	void oneWordChangeKeepsFingerprintsClose() {
		String before = "Privacy Policy\nWe collect your location data.\nWe retain data for 30 days.";
		String after = "Privacy Policy\nWe collect your precise location data.\nWe retain data for 30 days.";

		int distance = SimHashDistance.hammingDistance(simHash.fingerprint(before),
				simHash.fingerprint(after));

		assertThat(distance).isEqualTo(4);
		assertThat(SimHashDistance.similarity(simHash.fingerprint(before), simHash.fingerprint(after)))
				.isEqualTo(1.0 - (4 / 64.0));
	}

	@Test
	void distanceIsSymmetricForRealFingerprints() {
		String first = "Privacy Policy\nWe collect your location data.";
		String second = "Privacy Policy\nWe retain data for 90 days.";

		long firstPrint = simHash.fingerprint(first);
		long secondPrint = simHash.fingerprint(second);

		assertThat(SimHashDistance.hammingDistance(firstPrint, secondPrint))
				.isEqualTo(SimHashDistance.hammingDistance(secondPrint, firstPrint));
	}

	@Test
	void unicodeTextIsDeterministic() {
		String first = "Données personnelles conservées 30 jours.";
		String second = "Données personnelles conservées 90 jours.";

		assertThat(simHash.fingerprint(first)).isEqualTo(simHash.fingerprint(first));
		assertThat(simHash.fingerprint(second)).isEqualTo(simHash.fingerprint(second));
		assertThat(SimHashDistance.hammingDistance(simHash.fingerprint(first), simHash.fingerprint(second)))
				.isEqualTo(9);
	}

	@Test
	void separatorsAreEquivalentButCaseIsPreserved() {
		assertThat(simHash.fingerprint("data-sharing"))
				.isEqualTo(simHash.fingerprint("data sharing"));
		assertThat(simHash.fingerprint("data sharing"))
				.isEqualTo(simHash.fingerprint("data_sharing"));

		long upper = simHash.fingerprint("Hello World");
		long lower = simHash.fingerprint("hello world");
		assertThat(lower).isNotEqualTo(upper);
		assertThat(SimHashDistance.hammingDistance(upper, lower)).isEqualTo(20);
	}

	@Test
	void formattingEquivalentInputsShareFingerprintAfterNormalization() {
		PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
		String rawA = "Privacy Policy\nWe collect  your location data.\n";
		String rawB = "Privacy Policy\r\n  We collect your location   data.  \r\n";

		String canonicalA = normalizer.normalize(rawA);
		String canonicalB = normalizer.normalize(rawB);

		assertThat(canonicalB).isEqualTo(canonicalA);
		assertThat(simHash.fingerprint(canonicalB)).isEqualTo(simHash.fingerprint(canonicalA));
	}

	@Test
	void differentContentHasDeterministicFingerprintsWithoutDistanceAssumption() {
		String first = "We collect email addresses.";
		String second = "We retain data for 90 days.";

		assertThat(simHash.fingerprint(first)).isEqualTo(simHash.fingerprint(first));
		assertThat(simHash.fingerprint(second)).isEqualTo(simHash.fingerprint(second));
		assertThat(simHash.fingerprint(first)).isNotEqualTo(simHash.fingerprint(second));
		// No assertion on the magnitude of the distance: SimHash similarity
		// is probabilistic, so requiring a large distance for arbitrary
		// different documents would be an invalid assumption.
	}

	@Test
	void implementationDoesNotNormalizeInputItself() {
		// Case folding would erase this distinction; the normalizer also
		// preserves case, so a differing fingerprint here proves the SimHash
		// performs no case normalization of its own. Whitespace
		// canonicalization stays the caller's (normalizer's) job: SimHash
		// only splits the given string into tokens.
		assertThat(simHash.fingerprint("Hello World"))
				.isNotEqualTo(simHash.fingerprint("hello world"));
	}

	@Test
	void sha256AndSimHashProvideDifferentSignalsForSmallWordingChange() {
		Sha256PolicyContentHasher sha256 = new Sha256PolicyContentHasher();
		String textA = "Privacy Policy\nWe collect your location data.\nWe retain data for 30 days.";
		String textB = "Privacy Policy\nWe collect your precise location data.\nWe retain data for 30 days.";

		String shaA = sha256.hash(textA);
		String shaB = sha256.hash(textB);
		assertThat(shaB).isNotEqualTo(shaA);

		long simA = simHash.fingerprint(textA);
		long simB = simHash.fingerprint(textB);
		assertThat(simHash.fingerprint(textA)).isEqualTo(simA);
		assertThat(simHash.fingerprint(textB)).isEqualTo(simB);

		int distance = SimHashDistance.hammingDistance(simA, simB);
		assertThat(distance).isEqualTo(SimHashDistance.hammingDistance(simB, simA));
		assertThat(distance).isEqualTo(4);
		// Deliberately no threshold assertion (for example "distance < N
		// means same policy"): the exact hash diverges completely while the
		// similarity fingerprint stays close, and calibrating what counts as
		// "close" belongs to a later evaluation slice.
	}
}
