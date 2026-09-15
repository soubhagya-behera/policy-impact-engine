package com.soubhagya.policyimpactengine.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for {@link LineBasedPolicyDiffEngine} using
 * inline strings only. No network, no database, no Spring context.
 */
class LineBasedPolicyDiffEngineTest {

	private final PolicyDiffEngine engine = new LineBasedPolicyDiffEngine();

	@Test
	void identicalContentProducesNoChanges() {
		String content = "Privacy Policy\n\nWe collect email addresses.";

		PolicyDiffResult result = engine.diff(content, content);

		assertThat(result.changes()).isEmpty();
	}

	@Test
	void emptyOldAndNonEmptyNewProducesAdded() {
		PolicyDiffResult result = engine.diff("", "We collect email addresses.\nWe retain data.");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.ADDED, null, "We collect email addresses."),
				new PolicyChange(PolicyChangeType.ADDED, null, "We retain data."));
	}

	@Test
	void nonEmptyOldAndEmptyNewProducesRemoved() {
		PolicyDiffResult result = engine.diff("We collect email addresses.\nWe retain data.", "");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.REMOVED, "We collect email addresses.", null),
				new PolicyChange(PolicyChangeType.REMOVED, "We retain data.", null));
	}

	@Test
	void singleLineAddedInTheMiddleProducesAdded() {
		PolicyDiffResult result = engine.diff("A\nC", "A\nB\nC");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.ADDED, null, "B"));
	}

	@Test
	void singleLineRemovedFromTheMiddleProducesRemoved() {
		PolicyDiffResult result = engine.diff("A\nB\nC", "A\nC");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.REMOVED, "B", null));
	}

	@Test
	void singleLineChangedProducesModified() {
		PolicyDiffResult result = engine.diff(
				"We retain data for 30 days.",
				"We retain data for 90 days.");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED,
						"We retain data for 30 days.", "We retain data for 90 days."));
	}

	@Test
	void multipleAdditionsAreEachReported() {
		PolicyDiffResult result = engine.diff("A\nD", "A\nB\nC\nD");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.ADDED, null, "B"),
				new PolicyChange(PolicyChangeType.ADDED, null, "C"));
	}

	@Test
	void multipleRemovalsAreEachReported() {
		PolicyDiffResult result = engine.diff("A\nB\nC\nD", "A\nD");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.REMOVED, "B", null),
				new PolicyChange(PolicyChangeType.REMOVED, "C", null));
	}

	@Test
	void multipleModificationsAreEachReported() {
		PolicyDiffResult result = engine.diff(
				"H\nA\nM\nB\nF",
				"H\nA2\nM\nB2\nF");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED, "A", "A2"),
				new PolicyChange(PolicyChangeType.MODIFIED, "B", "B2"));
	}

	@Test
	void additionRemovalAndModificationInOneDocument() {
		PolicyDiffResult result = engine.diff(
				"H\nKEEP\nDROP\nCHANGE\nF",
				"H\nKEEP\nCHANGE2\nEXTRA\nF");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.REMOVED, "DROP", null),
				new PolicyChange(PolicyChangeType.REMOVED, "CHANGE", null),
				new PolicyChange(PolicyChangeType.ADDED, null, "CHANGE2"),
				new PolicyChange(PolicyChangeType.ADDED, null, "EXTRA"));
	}

	@Test
	void completelyUnrelatedDocumentsYieldOnlyAddedAndRemoved() {
		PolicyDiffResult result = engine.diff("alpha\nbeta", "one\ntwo\nthree");

		assertThat(result.changes())
				.hasSize(5)
				.allSatisfy(change -> assertThat(change.type())
						.isIn(PolicyChangeType.ADDED, PolicyChangeType.REMOVED));
		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.REMOVED, "alpha", null),
				new PolicyChange(PolicyChangeType.REMOVED, "beta", null),
				new PolicyChange(PolicyChangeType.ADDED, null, "one"),
				new PolicyChange(PolicyChangeType.ADDED, null, "two"),
				new PolicyChange(PolicyChangeType.ADDED, null, "three"));
	}

	@Test
	void multiParagraphPolicyExample() {
		String oldContent = "Privacy Policy\n\nWe collect email addresses.\n\nWe retain data for 30 days.";
		String newContent = "Privacy Policy\n\nWe collect email addresses and phone numbers."
				+ "\n\nWe retain data for 30 days.\n\nContact us with questions.";

		PolicyDiffResult result = engine.diff(oldContent, newContent);

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED,
						"We collect email addresses.", "We collect email addresses and phone numbers."),
				new PolicyChange(PolicyChangeType.ADDED, null, ""),
				new PolicyChange(PolicyChangeType.ADDED, null, "Contact us with questions."));
	}

	@Test
	void oldAndNewTextArePreservedExactly() {
		PolicyDiffResult result = engine.diff(
				"You may opt out of targeted advertising.",
				"We may share location data with partners.");

		assertThat(result.changes()).hasSize(1);
		PolicyChange change = result.changes().get(0);
		assertThat(change.type()).isEqualTo(PolicyChangeType.MODIFIED);
		assertThat(change.oldText()).isEqualTo("You may opt out of targeted advertising.");
		assertThat(change.newText()).isEqualTo("We may share location data with partners.");
	}

	@Test
	void changesAreInDocumentOrder() {
		PolicyDiffResult result = engine.diff(
				"one\ntwo\nthree\nfour\nfive",
				"one\nTWO\nthree\nFOUR\nfive\nsix");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED, "two", "TWO"),
				new PolicyChange(PolicyChangeType.MODIFIED, "four", "FOUR"),
				new PolicyChange(PolicyChangeType.ADDED, null, "six"));
	}

	@Test
	void repeatedExecutionProducesIdenticalResults() {
		String oldContent = "Personal Data\n\nWe collect your location data.\n\nExtra old line.";
		String newContent = "Personal Data\n\nWe collect your precise location data.\n\nExtra new line.";

		PolicyDiffResult first = engine.diff(oldContent, newContent);
		PolicyDiffResult second = engine.diff(oldContent, newContent);
		PolicyDiffResult third = engine.diff(oldContent, newContent);

		assertThat(second).isEqualTo(first);
		assertThat(third).isEqualTo(first);
		assertThat(second.changes()).containsExactlyElementsOf(first.changes());
	}

	@Test
	void punctuationAndCapitalizationArePreservedExactly() {
		PolicyDiffResult result = engine.diff(
				"We Retain Data For 30 days!!!",
				"we retain data for 30 days!!!");

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED,
						"We Retain Data For 30 days!!!", "we retain data for 30 days!!!"));
	}

	@Test
	void whitespaceDifferencesAreNotNormalizedAway() {
		PolicyDiffResult doubleSpaced = engine.diff("We  collect email.", "We collect email.");

		assertThat(doubleSpaced.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED, "We  collect email.", "We collect email."));

		PolicyDiffResult trailingSpace = engine.diff("Privacy Policy", "Privacy Policy ");

		assertThat(trailingSpace.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED, "Privacy Policy", "Privacy Policy "));

		PolicyDiffResult addedBlankLine = engine.diff("A\nB", "A\n\nB");

		assertThat(addedBlankLine.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.ADDED, null, ""));
	}

	@Test
	void nullInputsAreTreatedAsEmptyDocuments() {
		assertThat(engine.diff(null, null).changes()).isEmpty();

		assertThat(engine.diff(null, "A").changes()).containsExactly(
				new PolicyChange(PolicyChangeType.ADDED, null, "A"));

		assertThat(engine.diff("A", null).changes()).containsExactly(
				new PolicyChange(PolicyChangeType.REMOVED, "A", null));
	}

	@Test
	void trailingNewlineDenotesARealFinalLine() {
		assertThat(engine.diff("a\n", "a").changes()).containsExactly(
				new PolicyChange(PolicyChangeType.REMOVED, "", null));

		assertThat(engine.diff("a", "a\n").changes()).containsExactly(
				new PolicyChange(PolicyChangeType.ADDED, null, ""));
	}

	@Test
	void changeModelRejectsInconsistentShapes() {
		assertThatThrownBy(() -> new PolicyChange(PolicyChangeType.ADDED, "old", "new"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyChange(PolicyChangeType.ADDED, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyChange(PolicyChangeType.REMOVED, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyChange(PolicyChangeType.REMOVED, "old", "new"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyChange(PolicyChangeType.MODIFIED, "same", "same"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyChange(null, "old", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyDiffResult(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void resultChangeListIsImmutable() {
		PolicyDiffResult result = engine.diff("A", "B");

		assertThatThrownBy(() -> result.changes().add(
				new PolicyChange(PolicyChangeType.ADDED, null, "X")))
						.isInstanceOf(UnsupportedOperationException.class);
		assertThat(result.changes()).isInstanceOf(List.class);
	}
}
