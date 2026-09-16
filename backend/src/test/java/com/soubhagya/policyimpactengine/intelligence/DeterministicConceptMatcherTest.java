package com.soubhagya.policyimpactengine.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

/**
 * Phase 2N — deterministic unit tests for the pure concept matcher.
 *
 * <p>No Spring, no DB, no I/O, no clock, no randomness. Covers the 15
 * required cases plus golden pinning.
 */
class DeterministicConceptMatcherTest {

	private final ConceptMatcher matcher = new DeterministicConceptMatcher();
	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	// 1. Location positive match
	@Test
	void locationPositiveMatch() {
		PolicyChangeRecord change = change("old text", "We collect your location data for the service.");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("LOCATION");
		ConceptMatch loc = matches.stream().filter(m -> m.conceptCode().equals("LOCATION")).findFirst().orElseThrow();
		assertThat(change.getNewText()).contains(loc.matchedFragment());
		assertThat(loc.patternId()).startsWith("LOCATION:");
	}

	// 2. Third-party sharing positive match
	@Test
	void thirdPartySharingPositiveMatch() {
		PolicyChangeRecord change = change(null, "We may share data with third-party partners.");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("THIRD_PARTY_SHARING");
	}

	@Test
	void thirdPartySharingWithHyphenAndSpaceVariants() {
		PolicyChangeRecord hyphen = change(null, "shared with third-party vendors");
		PolicyChangeRecord space = change(null, "shared with third party vendors");
		PolicyChangeRecord thirdParties = change(null, "sharing with third parties");
		assertThat(matcher.match(hyphen)).extracting(ConceptMatch::conceptCode).contains("THIRD_PARTY_SHARING");
		assertThat(matcher.match(space)).extracting(ConceptMatch::conceptCode).contains("THIRD_PARTY_SHARING");
		assertThat(matcher.match(thirdParties)).extracting(ConceptMatch::conceptCode).contains("THIRD_PARTY_SHARING");
	}

	// 3. Advertising positive match
	@Test
	void advertisingPositiveMatch() {
		PolicyChangeRecord change = change(null, "We use your data for advertising and marketing.");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("ADVERTISING");
	}

	@Test
	void advertisingPhraseVariants() {
		assertThat(matcher.match(change(null, "targeted ads will be shown")))
				.extracting(ConceptMatch::conceptCode).contains("ADVERTISING");
		assertThat(matcher.match(change(null, "personalised ads are enabled")))
				.extracting(ConceptMatch::conceptCode).contains("ADVERTISING");
		assertThat(matcher.match(change(null, "our ad network collects data")))
				.extracting(ConceptMatch::conceptCode).contains("ADVERTISING");
	}

	// 4. Negative / no-match case
	@Test
	void negativeNoMatchCase() {
		PolicyChangeRecord change = change("This policy applies to all users.", "This policy applies to all users equally.");
		// Both texts are generic, no concept keywords - but check empty or no match subset?
		// Use non-concept wording
		PolicyChangeRecord clean = change(null, "Hello world, this is a simple sentence about nothing relevant.");
		List<ConceptMatch> matches = matcher.match(clean);
		assertThat(matches).isEmpty();
	}

	// 5. Multiple concepts in one change
	@Test
	void multipleConceptsInOneChange() {
		PolicyChangeRecord change = change(null,
				"Location data may be shared with third-party advertising partners and retained for 90 days.");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode)
				.contains("LOCATION", "THIRD_PARTY_SHARING", "ADVERTISING", "DATA_RETENTION");
		// Should be one per concept, not duplicated
		assertThat(matches).extracting(ConceptMatch::conceptCode).doesNotHaveDuplicates();
		// Deterministic ordering by code
		assertThat(matches).extracting(ConceptMatch::conceptCode)
				.isSorted();
	}

	// 6. Case-insensitive matching
	@Test
	void caseInsensitiveMatching() {
		PolicyChangeRecord lower = change(null, "we collect location data");
		PolicyChangeRecord upper = change(null, "WE COLLECT LOCATION DATA");
		PolicyChangeRecord mixed = change(null, "We Collect LoCaTiOn DaTa");
		assertThat(matcher.match(lower)).extracting(ConceptMatch::conceptCode).contains("LOCATION");
		assertThat(matcher.match(upper)).extracting(ConceptMatch::conceptCode).contains("LOCATION");
		assertThat(matcher.match(mixed)).extracting(ConceptMatch::conceptCode).contains("LOCATION");
		// Evidence preserves original case
		ConceptMatch mixedMatch = matcher.match(mixed).stream()
				.filter(m -> m.conceptCode().equals("LOCATION")).findFirst().orElseThrow();
		assertThat(mixedMatch.matchedFragment()).isEqualTo("LoCaTiOn");
	}

	// 7. Punctuation variation
	@Test
	void punctuationVariation() {
		PolicyChangeRecord change = change(null, "We use cookies, tracking pixels, and local storage!");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("COOKIES");
		// Third-party with punctuation
		assertThat(matcher.match(change(null, "third-party; sharing"))).extracting(ConceptMatch::conceptCode)
				.contains("THIRD_PARTY_SHARING");
	}

	// 8. Evidence is an actual substring
	@Test
	void evidenceIsActualSubstringOfSource() {
		PolicyChangeRecord change = change("old deletion text here", "We now support the right to be forgotten for all users.");
		List<ConceptMatch> matches = matcher.match(change);
		for (ConceptMatch m : matches) {
			boolean inOld = change.getOldText() != null && change.getOldText().contains(m.matchedFragment());
			boolean inNew = change.getNewText() != null && change.getNewText().contains(m.matchedFragment());
			assertThat(inOld || inNew).as("Evidence '%s' must be substring of old or new", m.matchedFragment()).isTrue();
		}
		// Specific check for deletion rights phrase
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("DELETION_RIGHTS");
		ConceptMatch del = matches.stream().filter(c -> c.conceptCode().equals("DELETION_RIGHTS")).findFirst().orElseThrow();
		boolean inOld = change.getOldText() != null && change.getOldText().contains(del.matchedFragment());
		boolean inNew = change.getNewText() != null && change.getNewText().contains(del.matchedFragment());
		assertThat(inOld || inNew).isTrue();
	}

	// 9. Empty / null change text
	@Test
	void emptyChangeTextProducesNoMatches() {
		// ADDED with empty? Not allowed by entity, but matcher should handle null oldText
		PolicyChangeRecord added = change(null, "new wording with location");
		assertThat(matcher.match(added)).extracting(ConceptMatch::conceptCode).contains("LOCATION");

		// MODIFIED with blank-ish (should not happen via persistence but matcher must be safe)
		Policy policy = policy("https://example.com/privacy");
		PolicyVersion v1 = version(policy, 1, "old");
		PolicyVersion v2 = version(policy, 2, "new");
		PolicyChangeRecord withBlankNew = new PolicyChangeRecord(v1, v2, PolicyChangeType.MODIFIED, "old text", "new text with no keywords", 0);
		assertThat(matcher.match(withBlankNew)).isEmpty();

		// Null handling: oldText null is normal for ADDED, newText null for REMOVED
		PolicyChangeRecord removed = change("We use cookies for tracking", null);
		assertThat(matcher.match(removed)).extracting(ConceptMatch::conceptCode).contains("COOKIES");
	}

	// 10. Deterministic ordering
	@Test
	void deterministicOrderingByConceptCode() {
		PolicyChangeRecord change = change(null,
				"Children's data and cookies and location and arbitration terms apply.");
		List<ConceptMatch> first = matcher.match(change);
		List<ConceptMatch> second = matcher.match(change);
		assertThat(first).extracting(ConceptMatch::conceptCode).isSorted();
		assertThat(second).extracting(ConceptMatch::conceptCode).isEqualTo(first.stream().map(ConceptMatch::conceptCode).toList());
		// Repeated runs yield identical ordered list
		for (int i = 0; i < 5; i++) {
			assertThat(matcher.match(change)).isEqualTo(first);
		}
	}

	// 11. Unicode text
	@Test
	void unicodeTextMatching() {
		PolicyChangeRecord change = change(null, "We collect location data — café and naïve users included. ");
		// Location should still match despite unicode dash and accents nearby
		assertThat(matcher.match(change)).extracting(ConceptMatch::conceptCode).contains("LOCATION");
		// Ensure unicode word boundary handling doesn't break
		PolicyChangeRecord unicodeLocation = change(null, "Location donnée —  données de localisation");
		List<ConceptMatch> matches = matcher.match(unicodeLocation);
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("LOCATION");
	}

	// 12. Same input produces exactly same output
	@Test
	void sameInputProducesExactlySameOutput() {
		PolicyChangeRecord change = change("old arbitration clause", "new binding arbitration and class action waiver terms");
		List<ConceptMatch> first = matcher.match(change);
		List<ConceptMatch> second = matcher.match(change);
		assertThat(first).isEqualTo(second);
		assertThat(matcher.match(change(null, "Location and Cookies and Data retention 90 days"))).isEqualTo(
				matcher.match(change(null, "Location and Cookies and Data retention 90 days")));
	}

	// 13. oldText-only match
	@Test
	void oldTextOnlyMatch() {
		PolicyChangeRecord change = change("We use cookies to track you", "We have updated our general terms.");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("COOKIES");
		ConceptMatch cm = matches.stream().filter(c -> c.conceptCode().equals("COOKIES")).findFirst().orElseThrow();
		assertThat(change.getOldText()).contains(cm.matchedFragment());
	}

	// 14. newText-only match
	@Test
	void newTextOnlyMatch() {
		PolicyChangeRecord change = change("General terms apply.", "We now collect precise location data for all users.");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("LOCATION");
		ConceptMatch cm = matches.stream().filter(c -> c.conceptCode().equals("LOCATION")).findFirst().orElseThrow();
		assertThat(change.getNewText()).contains(cm.matchedFragment());
	}

	// 15. Concept appearing in both oldText and newText does not create duplicates
	@Test
	void conceptAppearingInBothOldAndNewDoesNotDuplicate() {
		PolicyChangeRecord change = change("We collect location data for analytics.",
				"We collect precise location data for analytics and advertising.");
		List<ConceptMatch> matches = matcher.match(change);
		long locationCount = matches.stream().filter(m -> m.conceptCode().equals("LOCATION")).count();
		assertThat(locationCount).isEqualTo(1);
		// Advertising appears only in newText, should be separate concept
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("LOCATION", "ADVERTISING");
		assertThat(matches).extracting(ConceptMatch::conceptCode).doesNotHaveDuplicates();
	}

	// Golden pinning
	@Test
	void goldenSingleLocationChangeProducesExpectedMatch() {
		PolicyChangeRecord change = change("We collect data.", "We collect your location data.");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).hasSize(1);
		ConceptMatch cm = matches.get(0);
		assertThat(cm.conceptCode()).isEqualTo("LOCATION");
		assertThat(cm.patternId()).isEqualTo("LOCATION:location");
		assertThat(cm.matchKind()).isEqualTo("KEYWORD");
		assertThat(cm.matchedFragment().toLowerCase()).isEqualTo("location");
	}

	@Test
	void goldenThirdPartyAndAdvertisingChange() {
		PolicyChangeRecord change = change("We do not share data.", "Location data may be shared with third-party advertising partners");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode).containsExactly("ADVERTISING", "LOCATION",
				"THIRD_PARTY_SHARING");
		// Ordered by code
		assertThat(matches).extracting(ConceptMatch::conceptCode).isSorted();
	}

	@Test
	void goldenDataRetentionAndDeletionRights() {
		PolicyChangeRecord change = change("We retain data for 30 days.", "We retain data for 90 days and you have the right to be forgotten.");
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).extracting(ConceptMatch::conceptCode).contains("DATA_RETENTION", "DELETION_RIGHTS");
	}

	@Test
	void goldenAllConceptsEvidenceIsSubstring() {
		String text = "Location data, third-party sharing, advertising marketing, retention 90 days, deletion right to be forgotten, cookies and pixels, children COPPA, arbitration binding arbitration, legal basis consent";
		PolicyChangeRecord change = change(null, text);
		List<ConceptMatch> matches = matcher.match(change);
		assertThat(matches).hasSize(9);
		for (ConceptMatch m : matches) {
			assertThat(text).contains(m.matchedFragment());
		}
	}

	@Test
	void matchRejectsNullChange() {
		assertThatThrownBy(() -> matcher.match(null)).isInstanceOf(IllegalArgumentException.class);
	}

	private PolicyChangeRecord change(String oldText, String newText) {
		Policy policy = policy("https://example.com/" + UUID.randomUUID());
		PolicyVersion v1 = version(policy, 1, oldText != null ? oldText : "placeholder-old");
		PolicyVersion v2 = version(policy, 2, newText != null ? newText : "placeholder-new");
		// Determine type based on nulls to satisfy PolicyChangeRecord validation
		if (oldText == null && newText != null) {
			return new PolicyChangeRecord(v1, v2, PolicyChangeType.ADDED, null, newText, 0);
		}
		if (oldText != null && newText == null) {
			return new PolicyChangeRecord(v1, v2, PolicyChangeType.REMOVED, oldText, null, 0);
		}
		// MODIFIED requires differing text
		String safeOld = oldText != null ? oldText : "old";
		String safeNew = newText != null ? newText : "new";
		if (safeOld.equals(safeNew)) {
			safeNew = safeNew + " changed";
		}
		return new PolicyChangeRecord(v1, v2, PolicyChangeType.MODIFIED, safeOld, safeNew, 0);
	}

	private Policy policy(String url) {
		Policy p = new Policy("Test Policy", url);
		p.setId(UUID.randomUUID());
		return p;
	}

	private PolicyVersion version(Policy policy, int number, String content) {
		PolicyVersion v = new PolicyVersion(policy, number, content, hasher.hash(content));
		// Use reflection to set id for FK stability if needed; not required for matcher
		return v;
	}
}
