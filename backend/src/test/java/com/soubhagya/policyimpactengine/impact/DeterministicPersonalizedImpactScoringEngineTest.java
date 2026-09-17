package com.soubhagya.policyimpactengine.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

/**
 * Phase 2Q — pure unit tests (no Spring/DB).
 */
class DeterministicPersonalizedImpactScoringEngineTest {

	private final PersonalizedImpactScoringEngine engine =
			new DeterministicPersonalizedImpactScoringEngine();

	@Test
	void sensitivityZeroProducesZeroNone() {
		PersonalizedItemScore item = engine.scoreItem(new BigDecimal("8.00"), 0);
		assertThat(item.personalizedNormalized()).isZero();
		assertThat(item.personalizedBand()).isEqualTo(ImpactBand.NONE);
	}

	@Test
	void sensitivityOne() {
		PersonalizedItemScore item = engine.scoreItem(new BigDecimal("8.00"), 1);
		assertThat(item.personalizedBase()).isEqualByComparingTo(new BigDecimal("2.67"));
		assertThat(item.personalizedNormalized()).isEqualTo(27);
		assertThat(item.personalizedBand()).isEqualTo(ImpactBand.LOW);
	}

	@Test
	void sensitivityThreeIsNeutralIdentity() {
		PersonalizedItemScore item = engine.scoreItem(new BigDecimal("8.00"), 3);
		assertThat(item.personalizedBase()).isEqualByComparingTo(new BigDecimal("8.00"));
		assertThat(item.personalizedNormalized()).isEqualTo(80);
		assertThat(item.personalizedBand()).isEqualTo(ImpactBand.CRITICAL);
	}

	@Test
	void sensitivityFive() {
		PersonalizedItemScore item = engine.scoreItem(new BigDecimal("2.40"), 5);
		assertThat(item.personalizedBase()).isEqualByComparingTo(new BigDecimal("4.00"));
		assertThat(item.personalizedNormalized()).isEqualTo(40);
		assertThat(item.personalizedBand()).isEqualTo(ImpactBand.MEDIUM);
	}

	@Test
	void capAt100() {
		PersonalizedItemScore item = engine.scoreItem(new BigDecimal("10.00"), 5);
		assertThat(item.personalizedNormalized()).isEqualTo(100);
		assertThat(item.personalizedBand()).isEqualTo(ImpactBand.CRITICAL);
	}

	@Test
	void roundingHalfUp() {
		PersonalizedItemScore item = engine.scoreItem(new BigDecimal("2.45"), 1);
		assertThat(item.personalizedBase()).isEqualByComparingTo(new BigDecimal("0.82"));
		assertThat(item.personalizedNormalized()).isEqualTo(8);
	}

	@Test
	void bandBoundaries() {
		assertThat(ImpactBand.fromNormalizedScore(0)).isEqualTo(ImpactBand.NONE);
		assertThat(ImpactBand.fromNormalizedScore(29)).isEqualTo(ImpactBand.LOW);
		assertThat(ImpactBand.fromNormalizedScore(30)).isEqualTo(ImpactBand.MEDIUM);
		assertThat(ImpactBand.fromNormalizedScore(54)).isEqualTo(ImpactBand.MEDIUM);
		assertThat(ImpactBand.fromNormalizedScore(55)).isEqualTo(ImpactBand.HIGH);
		assertThat(ImpactBand.fromNormalizedScore(79)).isEqualTo(ImpactBand.HIGH);
		assertThat(ImpactBand.fromNormalizedScore(80)).isEqualTo(ImpactBand.CRITICAL);
		assertThat(engine.scoreItem(new BigDecimal("2.90"), 3).personalizedBand())
				.isEqualTo(ImpactBand.LOW);
		assertThat(engine.scoreItem(new BigDecimal("3.00"), 3).personalizedBand())
				.isEqualTo(ImpactBand.MEDIUM);
	}

	@Test
	void repeatedExecutionIdentical() {
		PersonalizedItemScore first = engine.scoreItem(new BigDecimal("6.40"), 4);
		PersonalizedItemScore second = engine.scoreItem(new BigDecimal("6.40"), 4);
		assertThat(first).isEqualTo(second);
		PersonalizedAssessmentResult all1 = engine.scoreAll(
				List.of(new BigDecimal("8.00"), new BigDecimal("2.40")), List.of(5, 0));
		PersonalizedAssessmentResult all2 = engine.scoreAll(
				List.of(new BigDecimal("8.00"), new BigDecimal("2.40")), List.of(5, 0));
		assertThat(all1).isEqualTo(all2);
	}

	@Test
	void maxAggregation() {
		PersonalizedAssessmentResult result = engine.scoreAll(
				List.of(new BigDecimal("8.00"), new BigDecimal("2.40"), new BigDecimal("4.80")),
				List.of(5, 0, 1));
		assertThat(result.items()).hasSize(3);
		assertThat(result.items().get(0).personalizedNormalized()).isEqualTo(100);
		assertThat(result.items().get(1).personalizedNormalized()).isZero();
		assertThat(result.items().get(2).personalizedNormalized()).isEqualTo(16);
		assertThat(result.aggregateScore()).isEqualTo(100);
		assertThat(result.aggregateBand()).isEqualTo(ImpactBand.CRITICAL);
		assertThat(result.personalizationRulesVersion()).isEqualTo(1);
	}

	@Test
	void emptyAggregationIsZeroNone() {
		PersonalizedAssessmentResult result = engine.scoreAll(List.of(), List.of());
		assertThat(result.aggregateScore()).isZero();
		assertThat(result.aggregateBand()).isEqualTo(ImpactBand.NONE);
		assertThat(result.personalizationRulesVersion()).isEqualTo(1);
	}

	@Test
	void rulesVersionConstant() {
		assertThat(DeterministicPersonalizedImpactScoringEngine.PERSONALIZATION_RULES_VERSION)
				.isEqualTo(1);
	}

	@Test
	void invalidInputsThrow() {
		assertThatThrownBy(() -> engine.scoreItem(null, 3))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.scoreItem(new BigDecimal("-1.00"), 3))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.scoreItem(new BigDecimal("1.00"), -1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.scoreItem(new BigDecimal("1.00"), 6))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.scoreAll(null, List.of(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.scoreAll(List.of(new BigDecimal("1.00")), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> engine.scoreAll(
				List.of(new BigDecimal("1.00")), List.of(1, 2)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
