package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Integration-style unit test demonstrating the intended pipeline
 * relationship: raw formatting-noisy text → PolicyTextNormalizer →
 * canonical text → PolicyContentHasher → stable hash.
 *
 * <p>Equivalent formatting noise must produce the same normalized text
 * and therefore the same hash, while naive hashing of the raw text
 * would diverge. No Spring, no DB, no HTTP.
 */
class PolicyNormalizationHashingIntegrationTest {

	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@Test
	void noisyFormattingProducesSameNormalizedTextAndSameHash() {
		// Small inline privacy-policy example with intentional formatting noise
		String noisyVariantA = "Privacy Policy\r\n"
				+ "\r\n"
				+ "\r\n"
				+ "We   collect your\temail address to operate this service.   \n"
				+ "Data Sharing\n"
				+ "We do not sell personal data.   See our opt-out page.\n"
				+ "\n"
				+ "\n"
				+ "\n"
				+ "Contact  \n"
				+ "Email privacy@example.com with questions.\n"
				+ "\n";

		String noisyVariantB = "Privacy Policy  \n"
				+ "\n"
				+ "  We collect your email address to operate this service. \n"
				+ "Data Sharing  \n"
				+ "We do not sell personal data. See our opt-out page.  \n"
				+ "\n"
				+ "Contact \n"
				+ "Email privacy@example.com with questions.  \n";

		String noisyVariantC = "  Privacy Policy  \n\n"
				+ "We\tcollect  your email address   to operate this service.\r\n"
				+ "Data Sharing  \r\n"
				+ "We do not sell personal data. See our opt-out page.  \n"
				+ "\n"
				+ "Contact\r\n"
				+ "Email privacy@example.com with questions.   \n";

		String canonicalA = normalizer.normalize(noisyVariantA);
		String canonicalB = normalizer.normalize(noisyVariantB);
		String canonicalC = normalizer.normalize(noisyVariantC);

		// 1. Same canonical text despite different raw formatting
		assertThat(canonicalA).isEqualTo(canonicalB).isEqualTo(canonicalC);
		assertThat(canonicalA).isEqualTo("""
				Privacy Policy

				We collect your email address to operate this service.
				Data Sharing
				We do not sell personal data. See our opt-out page.

				Contact
				Email privacy@example.com with questions.""");

		// 2. Same hash from canonical text — stable identity
		String hashA = hasher.hash(canonicalA);
		String hashB = hasher.hash(canonicalB);
		String hashC = hasher.hash(canonicalC);

		assertThat(hashA).isEqualTo(hashB).isEqualTo(hashC);
		assertThat(hashA).matches("[0-9a-f]{64}");

		// 3. Deterministic pipeline: re-normalize + re-hash is idempotent
		assertThat(hasher.hash(normalizer.normalize(noisyVariantA))).isEqualTo(hashA);
		assertThat(normalizer.normalize(canonicalA)).isEqualTo(canonicalA);
		assertThat(hasher.hash(normalizer.normalize(canonicalA))).isEqualTo(hashA);

		// 4. Hashing raw (un-normalized) text would NOT be stable — proves
		// that hashing itself does not normalize and must be fed canonical input
		String rawHashA = hasher.hash(noisyVariantA);
		String rawHashB = hasher.hash(noisyVariantB);
		assertThat(rawHashA).isNotEqualTo(rawHashB);
		assertThat(rawHashA).isNotEqualTo(hashA);
		assertThat(rawHashB).isNotEqualTo(hashA);
	}

	@Test
	void nullAndEmptyRawTextYieldSameStableHashViaPipeline() {
		String canonicalNull = normalizer.normalize(null);
		String canonicalEmpty = normalizer.normalize("");

		assertThat(canonicalNull).isEmpty();
		assertThat(canonicalEmpty).isEmpty();
		assertThat(canonicalNull).isEqualTo(canonicalEmpty);

		String hashNull = hasher.hash(canonicalNull);
		String hashEmpty = hasher.hash(canonicalEmpty);

		assertThat(hashNull).isEqualTo(hashEmpty);
		assertThat(hashNull).isEqualTo(hasher.hash(null));
		assertThat(hashNull).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
	}
}
