package com.soubhagya.policyimpactengine.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Line-based {@link PolicyDiffEngine} over already-normalized text.
 *
 * <p>Each line of the normalized content is one comparable block (the
 * upstream extractor already emits one line per document block and the
 * normalizer preserves paragraph separation), so a line diff is a block
 * diff here. Comparison is exact: no case folding, no trimming, no
 * whitespace canonicalization — inputs arrive normalized.
 *
 * <p><b>Algorithm.</b> Classical longest-common-subsequence dynamic
 * program over the two line sequences (JDK only, {@code O(n*m)} time and
 * space — ample for policy-document sizes), followed by a deterministic
 * backtrack from the end of both sequences. Ties broken uniformly in
 * favour of deletion, so the same inputs always yield the same minimal
 * edit script in document order. The engine inspects actual content;
 * it never compares hashes.
 *
 * <p><b>Modification classification rule (conservative).</b> The edit
 * script is scanned for maximal runs of consecutive non-kept operations
 * ("hunks"). A hunk containing exactly one removed line and exactly one
 * added line — one old block replaced by one new block at the same
 * logical position — is reported as a single {@code MODIFIED} change.
 * Every other hunk is reported as its primitive {@code ADDED}/{@code
 * REMOVED} changes in script order. Unrelated or multi-line replacements
 * are therefore never presented as semantically paired modifications.
 *
 * <p><b>Line splitting.</b> Content is split on {@code '\n'} exactly;
 * nothing else is interpreted. An empty document has zero lines. A
 * trailing newline therefore denotes a real (empty) final line — e.g.
 * {@code "a\n"} versus {@code "a"} diffs as one added empty line.
 *
 * <p>Stateless and thread-safe: no fields, no shared mutable state.
 * Plain Java with no Spring, persistence, I/O, clock, or randomness
 * dependencies.
 */
public class LineBasedPolicyDiffEngine implements PolicyDiffEngine {

	@Override
	public PolicyDiffResult diff(String oldContent, String newContent) {
		List<String> oldLines = splitLines(oldContent);
		List<String> newLines = splitLines(newContent);
		return new PolicyDiffResult(toChanges(editScript(oldLines, newLines)));
	}

	private static List<String> splitLines(String content) {
		if (content == null || content.isEmpty()) {
			return List.of();
		}
		return List.of(content.split("\n", -1));
	}

	private enum Operation {
		KEEP, REMOVE, ADD
	}

	private record ScriptStep(Operation operation, String text) {
	}

	/**
	 * Minimal edit script in document order via LCS backtrack.
	 */
	private static List<ScriptStep> editScript(List<String> oldLines, List<String> newLines) {
		int oldSize = oldLines.size();
		int newSize = newLines.size();
		int[][] lengths = new int[oldSize + 1][newSize + 1];
		for (int i = oldSize - 1; i >= 0; i--) {
			for (int j = newSize - 1; j >= 0; j--) {
				if (oldLines.get(i).equals(newLines.get(j))) {
					lengths[i][j] = lengths[i + 1][j + 1] + 1;
				}
				else {
					lengths[i][j] = Math.max(lengths[i + 1][j], lengths[i][j + 1]);
				}
			}
		}

		List<ScriptStep> reversed = new ArrayList<>(oldSize + newSize);
		int i = 0;
		int j = 0;
		while (i < oldSize && j < newSize) {
			if (oldLines.get(i).equals(newLines.get(j))) {
				reversed.add(new ScriptStep(Operation.KEEP, oldLines.get(i)));
				i++;
				j++;
			}
			else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
				reversed.add(new ScriptStep(Operation.REMOVE, oldLines.get(i)));
				i++;
			}
			else {
				reversed.add(new ScriptStep(Operation.ADD, newLines.get(j)));
				j++;
			}
		}
		while (i < oldSize) {
			reversed.add(new ScriptStep(Operation.REMOVE, oldLines.get(i)));
			i++;
		}
		while (j < newSize) {
			reversed.add(new ScriptStep(Operation.ADD, newLines.get(j)));
			j++;
		}
		return reversed;
	}

	/**
	 * Converts the script to changes, pairing single remove/add hunks as
	 * modifications per the classification rule above.
	 */
	private static List<PolicyChange> toChanges(List<ScriptStep> script) {
		List<PolicyChange> changes = new ArrayList<>();
		List<ScriptStep> hunk = new ArrayList<>();
		for (ScriptStep step : script) {
			if (step.operation() == Operation.KEEP) {
				flushHunk(hunk, changes);
			}
			else {
				hunk.add(step);
			}
		}
		flushHunk(hunk, changes);
		return Collections.unmodifiableList(changes);
	}

	private static void flushHunk(List<ScriptStep> hunk, List<PolicyChange> changes) {
		if (hunk.isEmpty()) {
			return;
		}
		long removals = hunk.stream().filter(step -> step.operation() == Operation.REMOVE).count();
		long additions = hunk.size() - removals;
		if (removals == 1 && additions == 1) {
			String oldText = hunk.stream()
					.filter(step -> step.operation() == Operation.REMOVE)
					.map(ScriptStep::text)
					.findFirst()
					.orElseThrow();
			String newText = hunk.stream()
					.filter(step -> step.operation() == Operation.ADD)
					.map(ScriptStep::text)
					.findFirst()
					.orElseThrow();
			changes.add(new PolicyChange(PolicyChangeType.MODIFIED, oldText, newText));
		}
		else {
			for (ScriptStep step : hunk) {
				if (step.operation() == Operation.REMOVE) {
					changes.add(new PolicyChange(PolicyChangeType.REMOVED, step.text(), null));
				}
				else {
					changes.add(new PolicyChange(PolicyChangeType.ADDED, null, step.text()));
				}
			}
		}
		hunk.clear();
	}
}
