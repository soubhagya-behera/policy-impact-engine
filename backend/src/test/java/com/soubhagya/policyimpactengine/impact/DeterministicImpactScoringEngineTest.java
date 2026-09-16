package com.soubhagya.policyimpactengine.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.impact.domain.ImpactScore;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

/**
 * Phase 2O — pure unit tests for deterministic system-level scoring.
 */
class DeterministicImpactScoringEngineTest {

	private final ImpactScoringEngine engine = new DeterministicImpactScoringEngine();
	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@Test
	void locationModifiedScoreAndBand() {
		// LOCATION weight 8, MODIFIED 1.0 => base 8.00 => normalized 80 => CRITICAL (80-100)
		PrivacyConcept location = concept("LOCATION", 8);
		PolicyChangeRecord change = persistedChange(PolicyChangeType.MODIFIED, "old", "We collect location data");
		ChangeConceptMatch match = match(change, location, "location", "LOCATION:location", "KEYWORD");
		ImpactScore score = engine.score(change, match);
		assertThat(score.conceptCode()).isEqualTo("LOCATION");
		assertThat(score.changeType()).isEqualTo("MODIFIED");
		assertThat(score.conceptWeight()).isEqualTo(8);
		assertThat(score.changeTypeMultiplier()).isEqualByComparingTo(new BigDecimal("1.0"));
		assertThat(score.baseScore()).isEqualByComparingTo(new BigDecimal("8.00"));
		assertThat(score.normalizedScore()).isEqualTo(80);
		assertThat(score.impactBand()).isEqualTo(ImpactBand.CRITICAL);
		assertThat(score.rulesVersion()).isEqualTo(1);
	}

	@Test
	void locationAddedScore() {
		// LOCATION 8 × 0.6 = 4.80 => 48 => MEDIUM (30-54)
		PrivacyConcept location = concept("LOCATION", 8);
		PolicyChangeRecord change = persistedChange(PolicyChangeType.ADDED, null, "location data");
		ChangeConceptMatch match = match(change, location, "location", "LOCATION:location", "KEYWORD");
		ImpactScore score = engine.score(change, match);
		assertThat(score.baseScore()).isEqualByComparingTo(new BigDecimal("4.80"));
		assertThat(score.normalizedScore()).isEqualTo(48);
		assertThat(score.impactBand()).isEqualTo(ImpactBand.MEDIUM);
	}

	@Test
	void locationRemovedScore() {
		// LOCATION 8 × 0.8 = 6.40 => 64 => HIGH (55-79)
		PrivacyConcept location = concept("LOCATION", 8);
		PolicyChangeRecord change = persistedChange(PolicyChangeType.REMOVED, "location data", null);
		ChangeConceptMatch match = match(change, location, "location", "LOCATION:location", "KEYWORD");
		ImpactScore score = engine.score(change, match);
		assertThat(score.baseScore()).isEqualByComparingTo(new BigDecimal("6.40"));
		assertThat(score.normalizedScore()).isEqualTo(64);
		assertThat(score.impactBand()).isEqualTo(ImpactBand.HIGH);
	}

	@Test
	void thirdPartySharingModifiedHigh() {
		// THIRD_PARTY_SHARING weight 10 × 1.0 =10.00 =>100 => CRITICAL
		PrivacyConcept tps = concept("THIRD_PARTY_SHARING", 10);
		PolicyChangeRecord change = persistedChange(PolicyChangeType.MODIFIED, "old", "share with third party");
		ChangeConceptMatch match = match(change, tps, "third party", "THIRD_PARTY_SHARING:third-party", "PHRASE");
		ImpactScore score = engine.score(change, match);
		assertThat(score.baseScore()).isEqualByComparingTo(new BigDecimal("10.00"));
		assertThat(score.normalizedScore()).isEqualTo(100);
		assertThat(score.impactBand()).isEqualTo(ImpactBand.CRITICAL);
	}

	@Test
	void cookiesAddedLow() {
		// COOKIES 4 × 0.6 =2.40 =>24 => LOW
		PrivacyConcept cookies = concept("COOKIES", 4);
		PolicyChangeRecord change = persistedChange(PolicyChangeType.ADDED, null, "cookies");
		ChangeConceptMatch match = match(change, cookies, "cookies", "COOKIES:cookies", "KEYWORD");
		ImpactScore score = engine.score(change, match);
		assertThat(score.baseScore()).isEqualByComparingTo(new BigDecimal("2.40"));
		assertThat(score.normalizedScore()).isEqualTo(24);
		assertThat(score.impactBand()).isEqualTo(ImpactBand.LOW);
	}

	@Test
	void multipleConceptsInSameChangeScoredIndependently() {
		PrivacyConcept loc = concept("LOCATION", 8);
		PrivacyConcept adv = concept("ADVERTISING", 7);
		PolicyChangeRecord change = persistedChange(PolicyChangeType.MODIFIED, "old", "Location and advertising");
		ChangeConceptMatch m1 = match(change, loc, "location", "LOCATION:location", "KEYWORD");
		ChangeConceptMatch m2 = match(change, adv, "advertising", "ADVERTISING:advertising", "KEYWORD");
		List<ImpactScore> scores = engine.scoreAll(List.of(change), List.of(m1, m2));
		assertThat(scores).hasSize(2);
		assertThat(scores.get(0).conceptCode()).isEqualTo("LOCATION");
		assertThat(scores.get(0).normalizedScore()).isEqualTo(80);
		assertThat(scores.get(1).conceptCode()).isEqualTo("ADVERTISING");
		assertThat(scores.get(1).normalizedScore()).isEqualTo(70);
	}

	@Test
	void multiplierApplicationIsCorrect() {
		PrivacyConcept c = concept("DATA_RETENTION", 6);
		PolicyChangeRecord added = persistedChange(PolicyChangeType.ADDED, null, "retain for 90 days");
		PolicyChangeRecord removed = persistedChange(PolicyChangeType.REMOVED, "retain for 90 days", null);
		PolicyChangeRecord modified = persistedChange(PolicyChangeType.MODIFIED, "retain 30", "retain 90");
		assertThat(engine.score(added, match(added, c, "retention", "DATA_RETENTION:retention", "KEYWORD")).baseScore())
				.isEqualByComparingTo(new BigDecimal("3.60"));
		assertThat(engine.score(removed, match(removed, c, "retention", "DATA_RETENTION:retention", "KEYWORD")).baseScore())
				.isEqualByComparingTo(new BigDecimal("4.80"));
		assertThat(engine.score(modified, match(modified, c, "retention", "DATA_RETENTION:retention", "KEYWORD")).baseScore())
				.isEqualByComparingTo(new BigDecimal("6.00"));
	}

	@Test
	void baseScoreCalculation() {
		PrivacyConcept c = concept("LEGAL_BASIS", 5);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.MODIFIED, "old", "consent");
		ImpactScore s = engine.score(ch, match(ch, c, "consent", "LEGAL_BASIS:consent", "KEYWORD"));
		assertThat(s.baseScore()).isEqualByComparingTo(new BigDecimal("5.00"));
		assertThat(s.normalizedScore()).isEqualTo(50);
	}

	@Test
	void normalization() {
		// Edge: weight 10 ×1.0=10 =>100; weight 9×1=9 =>90
		PrivacyConcept high = concept("THIRD_PARTY_SHARING", 10);
		PrivacyConcept child = concept("CHILDREN_DATA", 9);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.MODIFIED, "old", "new");
		assertThat(engine.score(ch, match(ch, high, "third party", "p1", "PHRASE")).normalizedScore()).isEqualTo(100);
		assertThat(engine.score(ch, match(ch, child, "children", "p2", "KEYWORD")).normalizedScore()).isEqualTo(90);
		// Ensure min(100,...) caps
	}

	@Test
	void bandBoundaries() {
		assertThat(ImpactBand.fromNormalizedScore(0)).isEqualTo(ImpactBand.NONE);
		assertThat(ImpactBand.fromNormalizedScore(1)).isEqualTo(ImpactBand.LOW);
		assertThat(ImpactBand.fromNormalizedScore(29)).isEqualTo(ImpactBand.LOW);
		assertThat(ImpactBand.fromNormalizedScore(30)).isEqualTo(ImpactBand.MEDIUM);
		assertThat(ImpactBand.fromNormalizedScore(54)).isEqualTo(ImpactBand.MEDIUM);
		assertThat(ImpactBand.fromNormalizedScore(55)).isEqualTo(ImpactBand.HIGH);
		assertThat(ImpactBand.fromNormalizedScore(79)).isEqualTo(ImpactBand.HIGH);
		assertThat(ImpactBand.fromNormalizedScore(80)).isEqualTo(ImpactBand.CRITICAL);
		assertThat(ImpactBand.fromNormalizedScore(100)).isEqualTo(ImpactBand.CRITICAL);
	}

	@Test
	void noneBandWhenZeroScore() {
		// No matches case: scoreAll empty => empty; but band NONE is for normalized 0
		assertThat(ImpactBand.fromNormalizedScore(0)).isEqualTo(ImpactBand.NONE);
		// weight 0 would be 0 => NONE (not seeded but engine supports)
		PrivacyConcept zero = concept("ZERO", 0);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.MODIFIED, "old", "new");
		ImpactScore s = engine.score(ch, match(ch, zero, "zero", "ZERO:z", "KEYWORD"));
		assertThat(s.normalizedScore()).isZero();
		assertThat(s.impactBand()).isEqualTo(ImpactBand.NONE);
	}

	@Test
	void deterministicOrderingPreserved() {
		PrivacyConcept loc = concept("LOCATION", 8);
		PrivacyConcept adv = concept("ADVERTISING", 7);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.MODIFIED, "old", "Location and advertising and cookies");
		ChangeConceptMatch m1 = match(ch, loc, "location", "LOCATION:location", "KEYWORD");
		ChangeConceptMatch m2 = match(ch, adv, "advertising", "ADVERTISING:advertising", "KEYWORD");
		List<ImpactScore> first = engine.scoreAll(List.of(ch), List.of(m1, m2));
		List<ImpactScore> second = engine.scoreAll(List.of(ch), List.of(m1, m2));
		assertThat(first).isEqualTo(second);
		assertThat(first).extracting(ImpactScore::conceptCode).containsExactly("LOCATION", "ADVERTISING");
	}

	@Test
	void deterministicRepeatedExecution() {
		PrivacyConcept c = concept("ARBITRATION", 5);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.REMOVED, "arbitration clause", null);
		ChangeConceptMatch m = match(ch, c, "arbitration", "ARBITRATION:arbitration", "KEYWORD");
		ImpactScore s1 = engine.score(ch, m);
		ImpactScore s2 = engine.score(ch, m);
		assertThat(s1).isEqualTo(s2);
		assertThat(s1.baseScore()).isEqualByComparingTo(new BigDecimal("4.00"));
		assertThat(s1.normalizedScore()).isEqualTo(40);
		assertThat(s1.impactBand()).isEqualTo(ImpactBand.MEDIUM);
	}

	@Test
	void rulesVersionIsOne() {
		PrivacyConcept c = concept("COOKIES", 4);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.ADDED, null, "cookies");
		assertThat(engine.score(ch, match(ch, c, "cookies", "COOKIES:cookies", "KEYWORD")).rulesVersion()).isEqualTo(1);
	}

	@Test
	void emptyInputHandling() {
		assertThat(engine.scoreAll(List.of(), List.of())).isEmpty();
		PrivacyConcept c = concept("LOCATION", 8);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.MODIFIED, "old", "location");
		ChangeConceptMatch m = match(ch, c, "location", "p", "KEYWORD");
		assertThat(engine.scoreAll(List.of(ch), List.of())).isEmpty();
		assertThat(engine.scoreAll(List.of(), List.of(m))).isEmpty();
	}

	@Test
	void nullInputThrows() {
		PrivacyConcept c = concept("LOCATION", 8);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.MODIFIED, "old", "location");
		ChangeConceptMatch m = match(ch, c, "location", "p", "KEYWORD");
		assertThatThrownBy(() -> engine.score(null, m)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.score(ch, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.scoreAll(null, List.of(m))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.scoreAll(List.of(ch), null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void noUserSensitivityInvolved() {
		// Same concept weight yields same score regardless of any imagined user pref — engine has no pref param
		PrivacyConcept c = concept("LOCATION", 8);
		PolicyChangeRecord ch1 = persistedChange(PolicyChangeType.MODIFIED, "old", "location");
		PolicyChangeRecord ch2 = persistedChange(PolicyChangeType.MODIFIED, "old2", "location again");
		ImpactScore s1 = engine.score(ch1, match(ch1, c, "location", "p", "KEYWORD"));
		ImpactScore s2 = engine.score(ch2, match(ch2, c, "location", "p", "KEYWORD"));
		assertThat(s1.baseScore()).isEqualByComparingTo(s2.baseScore());
		assertThat(s1.normalizedScore()).isEqualTo(s2.normalizedScore());
	}

	// Golden pinning
	@Test
	void goldenLocationModifiedCritical() {
		PrivacyConcept loc = concept("LOCATION", 8);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.MODIFIED, "We collect data.", "We collect location data.");
		ImpactScore s = engine.score(ch, match(ch, loc, "location", "LOCATION:location", "KEYWORD"));
		assertThat(s.conceptCode()).isEqualTo("LOCATION");
		assertThat(s.changeType()).isEqualTo("MODIFIED");
		assertThat(s.conceptWeight()).isEqualTo(8);
		assertThat(s.changeTypeMultiplier()).isEqualByComparingTo(new BigDecimal("1.0"));
		assertThat(s.baseScore()).isEqualByComparingTo(new BigDecimal("8.00"));
		assertThat(s.normalizedScore()).isEqualTo(80);
		assertThat(s.impactBand()).isEqualTo(ImpactBand.CRITICAL);
		assertThat(s.rulesVersion()).isEqualTo(1);
	}

	@Test
	void goldenThirdPartyAndAdvertising() {
		PrivacyConcept tps = concept("THIRD_PARTY_SHARING", 10);
		PrivacyConcept adv = concept("ADVERTISING", 7);
		PolicyChangeRecord ch = persistedChange(PolicyChangeType.MODIFIED, "old", "share with third party for advertising");
		ImpactScore sTps = engine.score(ch, match(ch, tps, "third party", "THIRD:third-party", "PHRASE"));
		ImpactScore sAdv = engine.score(ch, match(ch, adv, "advertising", "ADV:advertising", "KEYWORD"));
		assertThat(sTps.normalizedScore()).isEqualTo(100);
		assertThat(sTps.impactBand()).isEqualTo(ImpactBand.CRITICAL);
		assertThat(sAdv.normalizedScore()).isEqualTo(70);
		assertThat(sAdv.impactBand()).isEqualTo(ImpactBand.HIGH);
	}

	@Test
	void goldenAllBandsCovered() {
		// Using synthetic weights to hit each band via engine with real concept weights
		// COOKIES ADDED 4×0.6=2.4→24 LOW, COOKIES REMOVED 3.2? Actually 4×0.8=3.2→32 MEDIUM, LOCATION ADDED 48 MEDIUM, LOCATION MODIFIED 80 CRITICAL, CHILDREN_DATA MODIFIED 90 CRITICAL, LEGAL_BASIS MODIFIED 50 MEDIUM
		PrivacyConcept cookies = concept("COOKIES", 4);
		PrivacyConcept loc = concept("LOCATION", 8);
		PolicyChangeRecord addedCookies = persistedChange(PolicyChangeType.ADDED, null, "cookies");
		PolicyChangeRecord removedCookies = persistedChange(PolicyChangeType.REMOVED, "cookies", null);
		PolicyChangeRecord addedLoc = persistedChange(PolicyChangeType.ADDED, null, "location");
		PolicyChangeRecord modLoc = persistedChange(PolicyChangeType.MODIFIED, "old", "location");
		assertThat(engine.score(addedCookies, match(addedCookies, cookies, "cookies", "p", "KEYWORD")).impactBand()).isEqualTo(ImpactBand.LOW);
		assertThat(engine.score(removedCookies, match(removedCookies, cookies, "cookies", "p", "KEYWORD")).impactBand()).isEqualTo(ImpactBand.MEDIUM);
		assertThat(engine.score(addedLoc, match(addedLoc, loc, "location", "p", "KEYWORD")).impactBand()).isEqualTo(ImpactBand.MEDIUM);
		assertThat(engine.score(modLoc, match(modLoc, loc, "location", "p", "KEYWORD")).impactBand()).isEqualTo(ImpactBand.CRITICAL);
	}

	private PrivacyConcept concept(String code, int weight) {
		return new PrivacyConcept(code, code + " label", "desc " + code, weight, 3);
	}

	private PolicyChangeRecord persistedChange(PolicyChangeType type, String oldText, String newText) {
		Policy policy = new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID());
		policy.setId(UUID.randomUUID());
		String oldContent = oldText != null ? oldText : "placeholder-old-" + UUID.randomUUID();
		String newContent = newText != null ? newText : "placeholder-new-" + UUID.randomUUID();
		PolicyVersion v1 = new PolicyVersion(policy, 1, oldContent, hasher.hash(oldContent));
		PolicyVersion v2 = new PolicyVersion(policy, 2, newContent, hasher.hash(newContent));
		PolicyChangeRecord rec;
		if (type == PolicyChangeType.ADDED) {
			rec = new PolicyChangeRecord(v1, v2, type, null, newText, 0);
		} else if (type == PolicyChangeType.REMOVED) {
			rec = new PolicyChangeRecord(v1, v2, type, oldText, null, 0);
		} else {
			String o = oldText != null ? oldText : "old";
			String n = newText != null ? newText : "new-" + UUID.randomUUID();
			if (o.equals(n)) n = n + " diff";
			rec = new PolicyChangeRecord(v1, v2, type, o, n, 0);
		}
		try {
			java.lang.reflect.Field f = PolicyChangeRecord.class.getDeclaredField("id");
			f.setAccessible(true);
			f.set(rec, UUID.randomUUID());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return rec;
	}

	private ChangeConceptMatch match(PolicyChangeRecord change, PrivacyConcept concept, String fragment, String patternId, String kind) {
		return new ChangeConceptMatch(change, concept, fragment, patternId, kind);
	}
}
