package com.soubhagya.policyimpactengine.policy.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Default deterministic {@link PolicyTextNormalizer}.
 *
 * <p>Explicit, conservative rules:
 *
 * <ol>
 * <li>Null input yields the empty string.</li>
 * <li>Line endings are unified: CRLF and lone CR both become LF.</li>
 * <li>Runs of horizontal whitespace within a line (spaces, tabs, and
 * common Unicode spaces) collapse to a single space, preserving word
 * boundaries.</li>
 * <li>Each line is trimmed of leading/trailing whitespace.</li>
 * <li>Runs of blank lines collapse to a single blank line, and leading
 * or trailing blank lines are removed — paragraph separation is
 * preserved, blank-line noise is not.</li>
 * </ol>
 *
 * <p>Explicitly NOT done here: lowercasing, stemming, stop-word removal,
 * punctuation removal, or any semantic rewriting. The policy wording is
 * left untouched; only its formatting is canonicalized.
 *
 * <p>Deterministic and idempotent: the same input always yields the same
 * output, and normalizing an already-normalized text changes nothing
 * (both are tested properties). Plain Java with no external dependencies.
 */
public class DefaultPolicyTextNormalizer implements PolicyTextNormalizer {

	/**
	 * Horizontal whitespace collapsed to a single space: ASCII space and
	 * tab plus the common non-breaking and typographic spaces that leak
	 * from HTML extraction (for example {@code &nbsp;}).
	 */
	private static final Pattern HORIZONTAL_WHITESPACE =
			Pattern.compile("[ \\t\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+");

	@Override
	public String normalize(String text) {
		if (text == null) {
			return "";
		}
		String unified = text.replace("\r\n", "\n").replace('\r', '\n');
		String[] lines = unified.split("\n", -1);
		List<String> kept = new ArrayList<>(lines.length);
		boolean previousBlank = true;
		for (String line : lines) {
			String cleaned = HORIZONTAL_WHITESPACE.matcher(line).replaceAll(" ").trim();
			if (cleaned.isEmpty()) {
				if (!previousBlank) {
					kept.add("");
				}
				previousBlank = true;
			}
			else {
				kept.add(cleaned);
				previousBlank = false;
			}
		}
		while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
			kept.remove(kept.size() - 1);
		}
		return String.join("\n", kept);
	}
}
