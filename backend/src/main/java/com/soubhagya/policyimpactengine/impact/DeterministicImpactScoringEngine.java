package com.soubhagya.policyimpactengine.impact;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.impact.domain.ImpactScore;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;

/**
 * Phase 2O — deterministic system-level impact scorer.
 *
 * <p>Pure Java, no Spring/DB/HTTP/clock/randomness/LLM.
 *
 * <p>Formula (rulesVersion=1):
 * <pre>
 * conceptWeight = PrivacyConcept.default_weight
 * multiplier MODIFIED=1.0, REMOVED=0.8, ADDED=0.6
 * baseScore = conceptWeight × multiplier
 * normalized = min(100, round(baseScore×10))
 * band 0→NONE, 1-29 LOW, 30-54 MEDIUM, 55-79 HIGH, 80-100 CRITICAL
 * </pre>
 * Section criticality fixed at 1. default_sensitivity not used.
 */
public class DeterministicImpactScoringEngine implements ImpactScoringEngine {

	public static final int RULES_VERSION = 1;

	private static final Map<String, BigDecimal> MULTIPLIERS = Map.of(
			"ADDED", new BigDecimal("0.6"),
			"REMOVED", new BigDecimal("0.8"),
			"MODIFIED", new BigDecimal("1.0"));

	@Override
	public ImpactScore score(PolicyChangeRecord change, ChangeConceptMatch match) {
		if (change == null) {
			throw new IllegalArgumentException("Change must not be null");
		}
		if (match == null) {
			throw new IllegalArgumentException("Match must not be null");
		}
		if (match.getChange() != change
				&& !java.util.Objects.equals(match.getChange().getId(), change.getId())) {
			throw new IllegalArgumentException("Match does not belong to given change");
		}
		String changeType = change.getChangeType().name();
		BigDecimal multiplier = MULTIPLIERS.get(changeType);
		if (multiplier == null) {
			throw new IllegalStateException("Unknown change type: " + changeType);
		}
		int conceptWeight = match.getConcept().getDefaultWeight();
		String conceptCode = match.getConcept().getCode();
		BigDecimal baseScore = new BigDecimal(conceptWeight).multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
		int normalized = baseScore.multiply(BigDecimal.TEN).setScale(0, RoundingMode.HALF_UP).intValue();
		normalized = Math.min(100, normalized);
		ImpactBand band = ImpactBand.fromNormalizedScore(normalized);
		return new ImpactScore(conceptCode, changeType, conceptWeight, multiplier, baseScore, normalized, band,
				RULES_VERSION);
	}

	@Override
	public List<ImpactScore> scoreAll(List<PolicyChangeRecord> changes, List<ChangeConceptMatch> matches) {
		if (changes == null) {
			throw new IllegalArgumentException("Changes must not be null");
		}
		if (matches == null) {
			throw new IllegalArgumentException("Matches must not be null");
		}
		if (changes.isEmpty() || matches.isEmpty()) {
			return List.of();
		}
		// Map changeId for deterministic lookup
		Map<java.util.UUID, PolicyChangeRecord> changeById = new java.util.LinkedHashMap<>();
		for (PolicyChangeRecord c : changes) {
			Objects.requireNonNull(c.getId(), "Change id must not be null");
			changeById.put(c.getId(), c);
		}
		List<ImpactScore> result = new ArrayList<>(matches.size());
		// Deterministic order: input matches already ordered by ChangeConceptMatch persistence
		// (changeOrder then conceptCode). Keep that order.
		for (ChangeConceptMatch m : matches) {
			PolicyChangeRecord change = changeById.get(m.getChange().getId());
			if (change == null) {
				throw new IllegalStateException("Match references unknown change: " + m.getId());
			}
			result.add(score(change, m));
		}
		return List.copyOf(result);
	}
}
