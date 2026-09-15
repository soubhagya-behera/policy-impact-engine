package com.soubhagya.policyimpactengine.diff;

import java.util.List;

/**
 * Immutable, deterministically ordered result of comparing two policy
 * contents.
 *
 * <p>Changes are in document order: repeated execution with the same
 * inputs returns exactly the same ordered list. An empty list means the
 * two contents are identical — there is no separate changed flag.
 */
public record PolicyDiffResult(List<PolicyChange> changes) {

	public PolicyDiffResult {
		if (changes == null) {
			throw new IllegalArgumentException("Changes must not be null");
		}
		changes = List.copyOf(changes);
	}
}
