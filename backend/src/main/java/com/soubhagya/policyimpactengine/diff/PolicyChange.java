package com.soubhagya.policyimpactengine.diff;

/**
 * A single immutable textual change between two policy versions.
 *
 * <p>Shape by type:
 * <ul>
 * <li>{@code ADDED} — {@code oldText} is null, {@code newText} holds the added text.</li>
 * <li>{@code REMOVED} — {@code oldText} holds the removed text, {@code newText} is null.</li>
 * <li>{@code MODIFIED} — both hold the replaced texts, which are never equal.</li>
 * </ul>
 *
 * <p>Text is preserved exactly as supplied: no trimming, no case folding,
 * no whitespace canonicalization. Inputs are expected to be already
 * normalized by the upstream pipeline.
 */
public record PolicyChange(PolicyChangeType type, String oldText, String newText) {

	public PolicyChange {
		if (type == null) {
			throw new IllegalArgumentException("Change type must not be null");
		}
		switch (type) {
			case ADDED -> {
				if (oldText != null) {
					throw new IllegalArgumentException("ADDED change must have null oldText");
				}
				if (newText == null) {
					throw new IllegalArgumentException("ADDED change must have non-null newText");
				}
			}
			case REMOVED -> {
				if (oldText == null) {
					throw new IllegalArgumentException("REMOVED change must have non-null oldText");
				}
				if (newText != null) {
					throw new IllegalArgumentException("REMOVED change must have null newText");
				}
			}
			case MODIFIED -> {
				if (oldText == null || newText == null) {
					throw new IllegalArgumentException("MODIFIED change must have non-null oldText and newText");
				}
				if (oldText.equals(newText)) {
					throw new IllegalArgumentException("MODIFIED change must have differing oldText and newText");
				}
			}
		}
	}
}
