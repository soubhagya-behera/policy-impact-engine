package com.soubhagya.policyimpactengine.policy.fetch;

/**
 * Application-level abstraction for deterministic content hashing of
 * already-normalized policy text.
 *
 * <p>The hash is calculated from the canonical normalized text produced by
 * {@link PolicyTextNormalizer}, not from raw HTML, the HTTP response body,
 * or extracted-but-unnormalized text. The pipeline order is:
 * {@code raw HTML → extraction → normalization → hashing}.
 *
 * <p>Implementations are plain Java: no Spring, no database, no
 * repositories, no HTTP, no user data, no clocks, and no randomness.
 * Hashing is stateless, deterministic, and thread-safe.
 */
public interface PolicyContentHasher {

	/**
	 * Computes a deterministic lowercase hexadecimal SHA-256 digest of the
	 * given canonical normalized text.
	 *
	 * <p>Null input is defined as the hash of the empty string, consistent
	 * with {@link PolicyTextNormalizer} returning empty for null input. This
	 * keeps the normalizer→hasher composition null-safe and deterministic
	 * without introducing a separate null-hash semantic.
	 *
	 * @param normalizedText canonical normalized policy text; may be null
	 * @return 64-character lowercase hexadecimal SHA-256 digest; never null
	 */
	String hash(String normalizedText);
}
