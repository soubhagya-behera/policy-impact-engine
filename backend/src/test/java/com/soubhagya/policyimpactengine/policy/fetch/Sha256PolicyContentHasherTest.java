package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for {@link Sha256PolicyContentHasher}.
 *
 * <p>No Spring, no database, no HTTP, no network.
 */
class Sha256PolicyContentHasherTest {

	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@Test
	void nullInputHashesAsEmptyString() {
		String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
		assertThat(hasher.hash(null)).isEqualTo(emptyHash);
		assertThat(hasher.hash(null)).isEqualTo(hasher.hash(""));
	}

	@Test
	void emptyStringKnownVector() {
		// SHA-256("") from FIPS 180-4 / RFC 4634
		assertThat(hasher.hash("")).isEqualTo(
				"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
	}

	@Test
	void asciiKnownVectors() {
		// SHA-256("abc") and SHA-256("hello") — canonical test vectors
		assertThat(hasher.hash("abc")).isEqualTo(
				"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(hasher.hash("hello")).isEqualTo(
				"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
		assertThat(hasher.hash("hello world")).isEqualTo(
				"b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
	}

	@Test
	void unicodeTextHashesDeterministically() {
		// UTF-8 explicitly: café and Japanese greeting
		assertThat(hasher.hash("café")).isEqualTo(
				"850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e");
		assertThat(hasher.hash("こんにちは")).isEqualTo(
				"125aeadf27b0459b8760c13a3d80912dfa8a81a68261906f60d87f4a0268646c");
		// repeated call yields same result
		assertThat(hasher.hash("café")).isEqualTo(hasher.hash("café"));
		assertThat(hasher.hash("こんにちは")).isEqualTo(hasher.hash("こんにちは"));
		// with emoji / supplementary plane
		String emoji = "Privacy 🔒 policy — café";
		assertThat(hasher.hash(emoji)).isEqualTo(hasher.hash(emoji));
		assertThat(hasher.hash(emoji)).matches("[0-9a-f]{64}");
	}

	@Test
	void deterministicRepeatedHashing() {
		String input = "Privacy Policy\n\nWe collect your email.";
		String first = hasher.hash(input);
		String second = hasher.hash(input);
		String third = hasher.hash(input);
		assertThat(first).isEqualTo(second).isEqualTo(third);
		// new instance must agree — stateless
		PolicyContentHasher other = new Sha256PolicyContentHasher();
		assertThat(other.hash(input)).isEqualTo(first);
	}

	@Test
	void outputIsLowercaseHexadecimal64Chars() {
		String[] inputs = {"", "abc", "hello", "café", "こんにちは", "Privacy Policy"};
		for (String input : inputs) {
			String h = hasher.hash(input);
			assertThat(h).matches("[0-9a-f]{64}");
			assertThat(h).isEqualTo(h.toLowerCase());
			assertThat(h).hasSize(64);
		}
		// ensure no uppercase hex ever appears
		assertThat(hasher.hash("HELLO")).doesNotContain("A");
		assertThat(hasher.hash("HELLO")).doesNotContain("B");
		assertThat(hasher.hash("HELLO")).doesNotContain("C");
		assertThat(hasher.hash("HELLO")).doesNotContain("D");
		assertThat(hasher.hash("HELLO")).doesNotContain("E");
		assertThat(hasher.hash("HELLO")).doesNotContain("F");
	}

	@Test
	void differentContentProducesDifferentHashes() {
		assertThat(hasher.hash("hello")).isNotEqualTo(hasher.hash("hello "));
		assertThat(hasher.hash("hello")).isNotEqualTo(hasher.hash("Hello"));
		assertThat(hasher.hash("policy version 1")).isNotEqualTo(hasher.hash("policy version 2"));
		assertThat(hasher.hash("")).isNotEqualTo(hasher.hash(" "));
		assertThat(hasher.hash("a")).isNotEqualTo(hasher.hash("b"));
	}

	@Test
	void hashingDoesNotNormalizeWhitespaceOrFormatting() {
		// hasher itself is formatting-sensitive: different whitespace -> different hash
		// (normalization must happen *before* hashing, in PolicyTextNormalizer)
		assertThat(hasher.hash("hello world"))
				.isNotEqualTo(hasher.hash("hello  world"));
		assertThat(hasher.hash("hello world"))
				.isNotEqualTo(hasher.hash("hello\tworld"));
		assertThat(hasher.hash("hello"))
				.isNotEqualTo(hasher.hash("hello\n"));
		assertThat(hasher.hash("Privacy Policy\nData Sharing"))
				.isNotEqualTo(hasher.hash("Privacy Policy\r\nData Sharing"));
		assertThat(hasher.hash("We collect your email."))
				.isNotEqualTo(hasher.hash("We  collect  your  email."));
		assertThat(hasher.hash("Paragraph one.\n\nParagraph two."))
				.isNotEqualTo(hasher.hash("Paragraph one.\n\n\nParagraph two."));
		// hash("hello world") value pinned to prove no hidden normalize
		assertThat(hasher.hash("hello  world")).isEqualTo(
				"e519e9540ff8a84d732c6f6e8db424d9abf4125078130221763fa573067a9059");
	}
}
