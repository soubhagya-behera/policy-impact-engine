package com.soubhagya.policyimpactengine.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;

/**
 * Phase 2R — frozen rule catalog invariants (approved Phase 2R v1 decisions).
 */
class FrozenRecommendationRulesTest {

	@Test
	void rulesVersionIsOne() {
		assertThat(FrozenRecommendationRules.RECOMMENDATION_RULES_VERSION).isEqualTo(1);
	}

	@Test
	void fourRulesInDeclaredOrder() {
		assertThat(FrozenRecommendationRules.ALL_RULES_IN_DECLARED_ORDER)
				.extracting(RecommendationRule::ruleId)
				.containsExactly("REC-DELETION-RIGHTS-LOST", "REC-SHARING-OPT-OUT",
						"REC-REVIEW-SETTINGS", "REC-NONE-REQUIRED");
		assertThat(FrozenRecommendationRules.ALL_RULES_IN_DECLARED_ORDER)
				.extracting(RecommendationRule::ruleOrder)
				.containsExactly(1, 2, 3, 4);
		assertThat(FrozenRecommendationRules.ALL_RULES_IN_DECLARED_ORDER)
				.extracting(RecommendationRule::actionKind)
				.containsExactly(RecommendationActionKind.EXERCISE_DELETION,
						RecommendationActionKind.OPT_OUT_SHARING,
						RecommendationActionKind.REVIEW_SETTINGS,
						RecommendationActionKind.NONE_REQUIRED);
	}

	@Test
	void ruleOneIsDeletionRightsRemovedOrModifiedAndBandLess() {
		RecommendationRule rule = FrozenRecommendationRules.DELETION_RIGHTS_LOST;
		assertThat(rule.conceptCodes()).containsExactly("DELETION_RIGHTS");
		assertThat(rule.changeTypes()).containsExactlyInAnyOrder("REMOVED", "MODIFIED");
		assertThat(rule.personalizedBands()).isEmpty();
		assertThat(FrozenRecommendationRules.CONCEPT_RULES.get(0)).isSameAs(rule);
	}

	@Test
	void ruleTwoIsSharingOptOutWithLocationExcluded() {
		RecommendationRule rule = FrozenRecommendationRules.SHARING_OPT_OUT;
		assertThat(rule.conceptCodes()).containsExactlyInAnyOrder("THIRD_PARTY_SHARING", "ADVERTISING");
		assertThat(rule.conceptCodes()).doesNotContain("LOCATION");
		assertThat(rule.changeTypes()).isEmpty();
		assertThat(rule.personalizedBands())
				.containsExactlyInAnyOrder(ImpactBand.MEDIUM, ImpactBand.HIGH, ImpactBand.CRITICAL);
		assertThat(FrozenRecommendationRules.CONCEPT_RULES.get(1)).isSameAs(rule);
	}

	@Test
	void ruleThreeIsAnyConceptCatchAllAtMediumPlus() {
		RecommendationRule rule = FrozenRecommendationRules.REVIEW_SETTINGS;
		assertThat(rule.conceptCodes()).isEmpty();
		assertThat(rule.changeTypes()).isEmpty();
		assertThat(rule.personalizedBands())
				.containsExactlyInAnyOrder(ImpactBand.MEDIUM, ImpactBand.HIGH, ImpactBand.CRITICAL);
		assertThat(FrozenRecommendationRules.CONCEPT_RULES.get(2)).isSameAs(rule);
	}

	@Test
	void ruleFourIsAssessmentLevelClosure() {
		assertThat(FrozenRecommendationRules.CLOSURE_RULE).isSameAs(FrozenRecommendationRules.NONE_REQUIRED);
		assertThat(FrozenRecommendationRules.NONE_REQUIRED.ruleOrder()).isEqualTo(4);
		assertThat(FrozenRecommendationRules.NONE_REQUIRED.conceptCodes()).isEmpty();
		assertThat(FrozenRecommendationRules.NONE_REQUIRED.changeTypes()).isEmpty();
		assertThat(FrozenRecommendationRules.NONE_REQUIRED.personalizedBands()).isEmpty();
	}

	@Test
	void frozenDescriptorsAreImmutable() {
		Set<String> mutableConcepts = new HashSet<>(Set.of("DELETION_RIGHTS"));
		Set<ImpactBand> mutableBands = new HashSet<>(Set.of(ImpactBand.MEDIUM));
		RecommendationRule rule = new RecommendationRule(1, "REC-X", mutableConcepts, Set.of("REMOVED"),
				mutableBands, RecommendationActionKind.REVIEW_SETTINGS);
		mutableConcepts.add("MUTATED");
		mutableBands.add(ImpactBand.CRITICAL);
		assertThat(rule.conceptCodes()).containsExactly("DELETION_RIGHTS");
		assertThat(rule.personalizedBands()).containsExactly(ImpactBand.MEDIUM);
		List<RecommendationRule> rules = FrozenRecommendationRules.CONCEPT_RULES;
		assertThat(rules).hasSize(3);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> rules.add(rule))
				.isInstanceOf(UnsupportedOperationException.class);
	}
}
