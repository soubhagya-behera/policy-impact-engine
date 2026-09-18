package com.soubhagya.policyimpactengine.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;

/**
 * Phase 2R — pure unit tests for the deterministic recommendation engine
 * (no Spring/DB). Covers every rule positively and negatively, rule
 * ordering, personalized-band behavior, deduplication, ranking (including
 * the conceptCode final tie-break), determinism, and input validation.
 */
class DeterministicRecommendationEngineTest {

	private final RecommendationEngine engine = new DeterministicRecommendationEngine();

	private static ConceptItemScore item(String conceptCode, String changeType, int normalized) {
		return new ConceptItemScore(conceptCode, changeType, normalized,
				ImpactBand.fromNormalizedScore(normalized));
	}

	private static RecommendationInput input(List<ConceptItemScore> items, int aggregateScore) {
		return new RecommendationInput(items, aggregateScore,
				ImpactBand.fromNormalizedScore(aggregateScore));
	}

	@Test
	void deletionRightsRemovedFiresExerciseDeletion() {
		RecommendationResult result = engine.recommend(
				input(List.of(item("DELETION_RIGHTS", "REMOVED", 10)), 10));
		assertThat(result.recommendations()).hasSize(1);
		RecommendationOutcome outcome = result.recommendations().get(0);
		assertThat(outcome.ruleId()).isEqualTo("REC-DELETION-RIGHTS-LOST");
		assertThat(outcome.ruleOrder()).isEqualTo(1);
		assertThat(outcome.actionKind()).isEqualTo(RecommendationActionKind.EXERCISE_DELETION);
		assertThat(outcome.conceptCode()).isEqualTo("DELETION_RIGHTS");
		assertThat(outcome.personalizedNormalized()).isEqualTo(10);
		assertThat(outcome.personalizedBand()).isEqualTo(ImpactBand.LOW);
	}

	@Test
	void deletionRightsModifiedFiresExerciseDeletion() {
		RecommendationResult result = engine.recommend(
				input(List.of(item("DELETION_RIGHTS", "MODIFIED", 55)), 55));
		assertThat(result.recommendations())
				.extracting(RecommendationOutcome::actionKind)
				.containsExactly(RecommendationActionKind.EXERCISE_DELETION,
						RecommendationActionKind.REVIEW_SETTINGS);
		assertThat(result.recommendations().get(0).conceptCode()).isEqualTo("DELETION_RIGHTS");
		assertThat(result.recommendations().get(0).personalizedNormalized()).isEqualTo(55);
	}

	@Test
	void deletionRightsAddedDoesNotFireExerciseDeletion() {
		RecommendationResult result = engine.recommend(
				input(List.of(item("DELETION_RIGHTS", "ADDED", 80)), 80));
		assertThat(result.recommendations())
				.extracting(RecommendationOutcome::actionKind)
				.containsExactly(RecommendationActionKind.REVIEW_SETTINGS);
		assertThat(result.recommendations())
				.extracting(RecommendationOutcome::ruleId)
				.containsExactly("REC-REVIEW-SETTINGS");
	}

	@Test
	void deletionRightsRuleIsBandLess() {
		for (int normalized : new int[] { 0, 1, 24, 29, 30, 54, 55, 79, 80, 100 }) {
			RecommendationResult result = engine.recommend(
					input(List.of(item("DELETION_RIGHTS", "REMOVED", normalized)), normalized));
			assertThat(result.recommendations())
					.extracting(RecommendationOutcome::actionKind)
					.as("normalized=%s", normalized)
					.contains(RecommendationActionKind.EXERCISE_DELETION);
		}
	}

	@Test
	void sharingConceptsFireOptOutAtMediumHighCritical() {
		for (String concept : new String[] { "THIRD_PARTY_SHARING", "ADVERTISING" }) {
			for (int normalized : new int[] { 30, 55, 80 }) {
				RecommendationResult result = engine.recommend(
						input(List.of(item(concept, "MODIFIED", normalized)), normalized));
				assertThat(result.recommendations())
						.extracting(RecommendationOutcome::actionKind)
						.as("%s at %s", concept, normalized)
						.contains(RecommendationActionKind.OPT_OUT_SHARING,
								RecommendationActionKind.REVIEW_SETTINGS);
				RecommendationOutcome optOut = result.recommendations().stream()
						.filter(o -> o.actionKind() == RecommendationActionKind.OPT_OUT_SHARING)
						.findFirst().orElseThrow();
				assertThat(optOut.ruleId()).isEqualTo("REC-SHARING-OPT-OUT");
				assertThat(optOut.ruleOrder()).isEqualTo(2);
				assertThat(optOut.conceptCode()).isEqualTo(concept);
			}
		}
	}

	@Test
	void sharingConceptsDoNotFireOptOutAtLowOrNone() {
		for (String concept : new String[] { "THIRD_PARTY_SHARING", "ADVERTISING" }) {
			for (int normalized : new int[] { 0, 24 }) {
				RecommendationResult result = engine.recommend(
						input(List.of(item(concept, "MODIFIED", normalized)), normalized));
				assertThat(result.recommendations())
						.extracting(RecommendationOutcome::actionKind)
						.as("%s at %s", concept, normalized)
						.doesNotContain(RecommendationActionKind.OPT_OUT_SHARING,
								RecommendationActionKind.REVIEW_SETTINGS);
				assertThat(result.recommendations()).hasSize(1);
				assertThat(result.recommendations().get(0).actionKind())
						.isEqualTo(RecommendationActionKind.NONE_REQUIRED);
			}
		}
	}

	@Test
	void locationNeverFiresOptOut() {
		for (int normalized : new int[] { 30, 55, 80 }) {
			RecommendationResult result = engine.recommend(
					input(List.of(item("LOCATION", "MODIFIED", normalized)), normalized));
			assertThat(result.recommendations())
					.extracting(RecommendationOutcome::actionKind)
					.as("LOCATION at %s", normalized)
					.containsExactly(RecommendationActionKind.REVIEW_SETTINGS);
		}
	}

	@Test
	void reviewSettingsFiresForAllConceptsAtMediumHighCritical() {
		String[] concepts = { "ADVERTISING", "ARBITRATION", "CHILDREN_DATA", "COOKIES",
				"DATA_RETENTION", "DELETION_RIGHTS", "LEGAL_BASIS", "LOCATION",
				"THIRD_PARTY_SHARING" };
		for (String concept : concepts) {
			for (int normalized : new int[] { 30, 55, 80 }) {
				RecommendationResult result = engine.recommend(
						input(List.of(item(concept, "ADDED", normalized)), normalized));
				assertThat(result.recommendations())
						.extracting(RecommendationOutcome::actionKind)
						.as("%s at %s", concept, normalized)
						.contains(RecommendationActionKind.REVIEW_SETTINGS);
				RecommendationOutcome review = result.recommendations().stream()
						.filter(o -> o.ruleId().equals("REC-REVIEW-SETTINGS"))
						.findFirst().orElseThrow();
				assertThat(review.ruleOrder()).isEqualTo(3);
				assertThat(review.conceptCode()).isEqualTo(concept);
			}
		}
	}

	@Test
	void reviewSettingsNotFiredAtLowOrNone() {
		String[] concepts = { "ADVERTISING", "ARBITRATION", "CHILDREN_DATA", "COOKIES",
				"DATA_RETENTION", "DELETION_RIGHTS", "LEGAL_BASIS", "LOCATION",
				"THIRD_PARTY_SHARING" };
		for (String concept : concepts) {
			for (int normalized : new int[] { 0, 24 }) {
				RecommendationResult result = engine.recommend(
						input(List.of(item(concept, "MODIFIED", normalized)), normalized));
				assertThat(result.recommendations())
						.extracting(RecommendationOutcome::actionKind)
						.as("%s at %s", concept, normalized)
						.doesNotContain(RecommendationActionKind.REVIEW_SETTINGS);
			}
		}
	}

	@Test
	void noneRequiredEmittedExactlyOnceWhenNothingFires() {
		RecommendationResult noneAggregate = engine.recommend(input(List.of(), 0));
		assertThat(noneAggregate.recommendations()).hasSize(1);
		RecommendationOutcome none = noneAggregate.recommendations().get(0);
		assertThat(none.ruleId()).isEqualTo("REC-NONE-REQUIRED");
		assertThat(none.ruleOrder()).isEqualTo(4);
		assertThat(none.actionKind()).isEqualTo(RecommendationActionKind.NONE_REQUIRED);
		assertThat(none.conceptCode()).isNull();
		assertThat(none.personalizedNormalized()).isZero();
		assertThat(none.personalizedBand()).isEqualTo(ImpactBand.NONE);

		RecommendationResult lowAggregate = engine.recommend(
				input(List.of(item("COOKIES", "ADDED", 24)), 24));
		assertThat(lowAggregate.recommendations()).hasSize(1);
		RecommendationOutcome low = lowAggregate.recommendations().get(0);
		assertThat(low.actionKind()).isEqualTo(RecommendationActionKind.NONE_REQUIRED);
		assertThat(low.conceptCode()).isNull();
		assertThat(low.personalizedNormalized()).isEqualTo(24);
		assertThat(low.personalizedBand()).isEqualTo(ImpactBand.LOW);
	}

	@Test
	void noneRequiredNotEmittedWhenAnyRuleFires() {
		RecommendationResult result = engine.recommend(
				input(List.of(item("THIRD_PARTY_SHARING", "MODIFIED", 30)), 30));
		assertThat(result.recommendations()).isNotEmpty();
		assertThat(result.recommendations())
				.extracting(RecommendationOutcome::actionKind)
				.doesNotContain(RecommendationActionKind.NONE_REQUIRED);
	}

	@Test
	void noneRequiredNotEmittedAtMediumAggregateWithoutFiredRules() {
		// Degenerate synthetic input (real Phase 2Q data cannot produce it:
		// aggregate = max item score, and any MEDIUM+ item fires rule 3):
		// LOW items only, but a MEDIUM aggregate. No rule fires and the
		// closure applies only to NONE/LOW, so the result is empty.
		RecommendationResult result = engine.recommend(
				new RecommendationInput(List.of(item("COOKIES", "ADDED", 24)), 30, ImpactBand.MEDIUM));
		assertThat(result.recommendations()).isEmpty();
	}

	@Test
	void dedupSameActionKindSameConceptKeepsHighestScore() {
		RecommendationResult result = engine.recommend(input(
				List.of(item("DELETION_RIGHTS", "REMOVED", 30),
						item("DELETION_RIGHTS", "REMOVED", 80)),
				80));
		assertThat(result.recommendations())
				.extracting(RecommendationOutcome::actionKind)
				.containsExactly(RecommendationActionKind.EXERCISE_DELETION,
						RecommendationActionKind.REVIEW_SETTINGS);
		assertThat(result.recommendations().get(0).personalizedNormalized()).isEqualTo(80);
		assertThat(result.recommendations().get(1).personalizedNormalized()).isEqualTo(80);
	}

	@Test
	void dedupAppliesWithinCatchAllAcrossMultipleItems() {
		RecommendationResult result = engine.recommend(input(
				List.of(item("COOKIES", "ADDED", 30), item("COOKIES", "MODIFIED", 55)),
				55));
		assertThat(result.recommendations()).hasSize(1);
		assertThat(result.recommendations().get(0).actionKind())
				.isEqualTo(RecommendationActionKind.REVIEW_SETTINGS);
		assertThat(result.recommendations().get(0).personalizedNormalized()).isEqualTo(55);
	}

	@Test
	void differentActionKindsForSameConceptAllowed() {
		RecommendationResult result = engine.recommend(
				input(List.of(item("DELETION_RIGHTS", "REMOVED", 80)), 80));
		assertThat(result.recommendations())
				.extracting(RecommendationOutcome::actionKind)
				.containsExactly(RecommendationActionKind.EXERCISE_DELETION,
						RecommendationActionKind.REVIEW_SETTINGS);
		assertThat(result.recommendations())
				.allSatisfy(o -> assertThat(o.conceptCode()).isEqualTo("DELETION_RIGHTS"));
	}

	@Test
	void rankingIsScoreDescThenRuleOrderAscThenConceptCodeAsc() {
		RecommendationResult result = engine.recommend(input(
				List.of(item("DELETION_RIGHTS", "REMOVED", 80),
						item("ADVERTISING", "MODIFIED", 80),
						item("DATA_RETENTION", "MODIFIED", 80),
						item("ARBITRATION", "MODIFIED", 30),
						item("COOKIES", "ADDED", 24)),
				80));
		assertThat(result.recommendations()).extracting(
				RecommendationOutcome::actionKind,
				RecommendationOutcome::conceptCode,
				RecommendationOutcome::ruleOrder,
				RecommendationOutcome::personalizedNormalized
		).containsExactly(
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.EXERCISE_DELETION, "DELETION_RIGHTS", 1, 80),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.OPT_OUT_SHARING, "ADVERTISING", 2, 80),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.REVIEW_SETTINGS, "ADVERTISING", 3, 80),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.REVIEW_SETTINGS, "DATA_RETENTION", 3, 80),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.REVIEW_SETTINGS, "DELETION_RIGHTS", 3, 80),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.REVIEW_SETTINGS, "ARBITRATION", 3, 30));
	}

	@Test
	void repeatedExecutionProducesIdenticalResults() {
		List<ConceptItemScore> items = List.of(
				item("DELETION_RIGHTS", "REMOVED", 80),
				item("ADVERTISING", "MODIFIED", 80),
				item("LOCATION", "MODIFIED", 80),
				item("COOKIES", "ADDED", 24));
		RecommendationResult first = engine.recommend(input(items, 80));
		RecommendationResult second = engine.recommend(input(items, 80));
		assertThat(first).isEqualTo(second);
		assertThat(first.hashCode()).isEqualTo(second.hashCode());
	}

	@Test
	void resultCarriesFrozenRulesVersion() {
		RecommendationResult result = engine.recommend(input(List.of(), 0));
		assertThat(result.recommendationRulesVersion())
				.isEqualTo(FrozenRecommendationRules.RECOMMENDATION_RULES_VERSION)
				.isEqualTo(1);
	}

	@Test
	void unknownConceptHandledByCatchAll() {
		RecommendationResult result = engine.recommend(
				input(List.of(item("FUTURE_CONCEPT", "MODIFIED", 30)), 30));
		assertThat(result.recommendations())
				.extracting(RecommendationOutcome::actionKind)
				.containsExactly(RecommendationActionKind.REVIEW_SETTINGS);
	}

	@Test
	void invalidInputsRejected() {
		assertThatThrownBy(() -> engine.recommend(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ConceptItemScore("COOKIES", "ADDED", 30, ImpactBand.LOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ConceptItemScore("COOKIES", "ADDED", 101, ImpactBand.CRITICAL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ConceptItemScore(" ", "ADDED", 30, ImpactBand.MEDIUM))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ConceptItemScore("COOKIES", " ", 30, ImpactBand.MEDIUM))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecommendationInput(List.of(), 30, ImpactBand.LOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecommendationInput(null, 0, ImpactBand.NONE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecommendationInput(List.of(), -1, ImpactBand.NONE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecommendationOutcome("REC-X", 0,
				RecommendationActionKind.REVIEW_SETTINGS, "COOKIES", 30, ImpactBand.MEDIUM))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecommendationOutcome("REC-X", 1,
				RecommendationActionKind.NONE_REQUIRED, "COOKIES", 0, ImpactBand.NONE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecommendationOutcome("REC-X", 1,
				RecommendationActionKind.REVIEW_SETTINGS, null, 30, ImpactBand.MEDIUM))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
