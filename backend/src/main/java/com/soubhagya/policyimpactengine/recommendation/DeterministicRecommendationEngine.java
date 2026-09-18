package com.soubhagya.policyimpactengine.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;

/**
 * Phase 2R — deterministic rule evaluator over the frozen
 * {@link FrozenRecommendationRules}.
 *
 * <p>Pure Java, no Spring/DB/HTTP/clock/randomness/LLM.
 *
 * <p><b>Execution semantics (rules version 1):</b>
 * <ol>
 * <li>Concept-level rules 1–3 are evaluated in declared order against every
 * input item; a rule matches when the item's concept code is in the rule's
 * concept-code set (or the set is empty), the change type is in the rule's
 * set (or empty), and the personalized band is in the rule's set (or the rule
 * is band-less).</li>
 * <li>Candidates are deduplicated by the key {@code (actionKind,
 * conceptCode)}: the highest personalized score wins; ties fall to the lower
 * rule order, then to the lexicographically lower rule id.</li>
 * <li>If no candidate exists and the aggregate band is NONE or LOW, exactly
 * one assessment-level {@code NONE_REQUIRED} outcome is emitted with a null
 * concept code; its score/band snapshot the aggregate. If the aggregate band
 * is MEDIUM+ but no rule fired (degenerate input), the result is empty.</li>
 * <li>Outcomes are ranked by personalized score (descending), rule order
 * (ascending), then concept code (ascending — the final deterministic
 * tie-break).</li>
 * </ol>
 *
 * <p>Rules are code-defined and frozen ({@code RECOMMENDATION_RULES_VERSION
 * = 1}); the engine's execution semantics never change when rules are added.
 */
public class DeterministicRecommendationEngine implements RecommendationEngine {

	private static final Comparator<RecommendationOutcome> RANKING =
			Comparator.comparingInt(RecommendationOutcome::personalizedNormalized).reversed()
					.thenComparingInt(RecommendationOutcome::ruleOrder)
					.thenComparing(RecommendationOutcome::conceptCode,
							Comparator.nullsLast(Comparator.naturalOrder()));

	@Override
	public RecommendationResult recommend(RecommendationInput input) {
		if (input == null) {
			throw new IllegalArgumentException("Input must not be null");
		}
		Map<String, RecommendationOutcome> bestByActionAndConcept = new LinkedHashMap<>();
		for (RecommendationRule rule : FrozenRecommendationRules.CONCEPT_RULES) {
			for (ConceptItemScore item : input.items()) {
				if (matches(rule, item)) {
					RecommendationOutcome candidate = new RecommendationOutcome(
							rule.ruleId(), rule.ruleOrder(), rule.actionKind(),
							item.conceptCode(), item.personalizedNormalized(), item.personalizedBand());
					String key = rule.actionKind().name() + "|" + item.conceptCode();
					RecommendationOutcome existing = bestByActionAndConcept.get(key);
					if (existing == null || candidateWins(candidate, existing)) {
						bestByActionAndConcept.put(key, candidate);
					}
				}
			}
		}
		List<RecommendationOutcome> outcomes = new ArrayList<>(bestByActionAndConcept.values());
		if (outcomes.isEmpty() && isClosureBand(input.aggregateBand())) {
			outcomes.add(new RecommendationOutcome(
					FrozenRecommendationRules.CLOSURE_RULE.ruleId(),
					FrozenRecommendationRules.CLOSURE_RULE.ruleOrder(),
					FrozenRecommendationRules.CLOSURE_RULE.actionKind(),
					null, input.aggregateScore(), input.aggregateBand()));
		}
		outcomes.sort(RANKING);
		return new RecommendationResult(
				List.copyOf(outcomes), FrozenRecommendationRules.RECOMMENDATION_RULES_VERSION);
	}

	private boolean matches(RecommendationRule rule, ConceptItemScore item) {
		if (!rule.conceptCodes().isEmpty() && !rule.conceptCodes().contains(item.conceptCode())) {
			return false;
		}
		if (!rule.changeTypes().isEmpty() && !rule.changeTypes().contains(item.changeType())) {
			return false;
		}
		if (!rule.personalizedBands().isEmpty() && !rule.personalizedBands().contains(item.personalizedBand())) {
			return false;
		}
		return true;
	}

	/**
	 * Dedup winner within one (actionKind, conceptCode) key: higher score
	 * first, then lower rule order, then lower rule id (concept code is equal
	 * within a key).
	 */
	private boolean candidateWins(RecommendationOutcome candidate, RecommendationOutcome existing) {
		if (candidate.personalizedNormalized() != existing.personalizedNormalized()) {
			return candidate.personalizedNormalized() > existing.personalizedNormalized();
		}
		if (candidate.ruleOrder() != existing.ruleOrder()) {
			return candidate.ruleOrder() < existing.ruleOrder();
		}
		return candidate.ruleId().compareTo(existing.ruleId()) < 0;
	}

	private boolean isClosureBand(ImpactBand aggregateBand) {
		return aggregateBand == ImpactBand.NONE || aggregateBand == ImpactBand.LOW;
	}
}
