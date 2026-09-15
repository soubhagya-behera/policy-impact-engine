package com.soubhagya.policyimpactengine.policy.fetch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * JDK SHA-256 {@link PolicyContentHasher}.
 *
 * <p>Computes the SHA-256 digest of the UTF-8 bytes of the normalized text
 * and returns a deterministic lowercase hexadecimal string.
 *
 * <p>Properties:
 * <ul>
 * <li>Deterministic — same input always yields same output.</li>
 * <li>Stateless — no mutable fields, no shared {@link MessageDigest} instance.</li>
 * <li>Thread-safe — a new {@code MessageDigest} is created per invocation.</li>
 * <li>Independent of Spring, database, HTTP, users, time, and randomness.</li>
 * <li>UTF-8 is used explicitly; platform default encoding is never relied upon.</li>
 * <li>Null input yields the hash of the empty string.</li>
 * </ul>
 */
public class Sha256PolicyContentHasher implements PolicyContentHasher {

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	@Override
	public String hash(String normalizedText) {
		String input = normalizedText == null ? "" : normalizedText;
		byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
		byte[] digest = sha256(bytes);
		return toLowerHex(digest);
	}

	private static byte[] sha256(byte[] input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return md.digest(input);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

	private static String toLowerHex(byte[] bytes) {
		char[] out = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			int v = bytes[i] & 0xFF;
			out[i * 2] = HEX[v >>> 4];
			out[i * 2 + 1] = HEX[v & 0x0F];
		}
		return new String(out);
	}
}
