package com.soubhagya.policyimpactengine.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Standard deterministic 64-bit SimHash ({@link PolicySimHash}) over
 * already-normalized policy text. JDK only.
 *
 * <p><b>Tokenization.</b> The input is split on every maximal run of
 * characters that are not Unicode letters or digits
 * ({@code [^\p{L}\p{N}]+}, Unicode-aware via {@link Pattern}). Each
 * non-empty token is one weighted occurrence (weight 1 per occurrence, so
 * repeated tokens weigh proportionally more). Case is preserved
 * ({@code "Data"} and {@code "data"} are different tokens); there is no
 * lowercasing, stemming, stop-word removal, synonym handling, or any other
 * semantic rewriting. Whitespace (including newlines), punctuation, and
 * symbols act purely as separators. An input with no tokens (null, empty,
 * or separator-only text) yields fingerprint {@code 0L}.
 *
 * <p>This class performs tokenization only. It never normalizes raw input:
 * whitespace canonicalization, line-ending unification, and trimming are
 * the caller's ({@code PolicyTextNormalizer}'s) job, and inputs are assumed
 * to arrive already normalized.
 *
 * <p><b>Token hashing.</b> Each token is hashed to 64 bits with FNV-1a
 * (offset basis {@code 0xcbf29ce484222325L}, prime
 * {@code 0x100000001b3L}) over its UTF-16 code units in order. FNV-1a is
 * chosen because it is simple, allocation-free, and bit-for-bit
 * deterministic on every JVM — no platform-default charset, no locale, and
 * no native library is involved.
 *
 * <p><b>Combination (Charikar SimHash).</b> A 64-slot integer accumulator
 * sums each token occurrence's contribution: {@code +1} where the token
 * hash has bit {@code 1}, {@code -1} where it has bit {@code 0} (bit index
 * 0 is the least significant bit). The fingerprint sets bit {@code i} iff
 * the accumulator at {@code i} is strictly positive; ties (including the
 * all-zero accumulator of an empty document) resolve to {@code 0}. Tokens
 * are consumed in encounter order and accumulation is order-independent, so
 * no hash-map iteration order or other unordered traversal can affect the
 * result.
 *
 * <p>Stateless and thread-safe: no fields, no shared mutable state. Plain
 * Java with no Spring, persistence, I/O, clock, or randomness dependencies.
 */
public class DefaultPolicySimHash implements PolicySimHash {

	private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

	private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

	@Override
	public long fingerprint(String normalizedText) {
		List<String> tokens = tokenize(normalizedText);
		int[] sums = new int[SimHashDistance.FINGERPRINT_BITS];
		for (String token : tokens) {
			long tokenHash = fnv1a64(token);
			for (int bit = 0; bit < sums.length; bit++) {
				if ((tokenHash & (1L << bit)) != 0) {
					sums[bit] += 1;
				}
				else {
					sums[bit] -= 1;
				}
			}
		}
		long fingerprint = 0L;
		for (int bit = 0; bit < sums.length; bit++) {
			if (sums[bit] > 0) {
				fingerprint |= (1L << bit);
			}
		}
		return fingerprint;
	}

	/**
	 * Splits the input into non-empty tokens on separator runs. Null yields
	 * no tokens.
	 */
	static List<String> tokenize(String text) {
		List<String> tokens = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return tokens;
		}
		for (String token : SEPARATORS.split(text)) {
			if (!token.isEmpty()) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	/**
	 * 64-bit FNV-1a over the token's UTF-16 code units in order.
	 */
	static long fnv1a64(String token) {
		long hash = FNV_OFFSET_BASIS;
		for (int i = 0; i < token.length(); i++) {
			hash ^= token.charAt(i);
			hash *= FNV_PRIME;
		}
		return hash;
	}
}
