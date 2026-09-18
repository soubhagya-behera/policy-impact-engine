package com.soubhagya.policyimpactengine.recommendation;

import java.util.Set;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;

/**
 * Phase 2R — immutable, code-defined recommendation rule descriptor.
 *
 * <p>Pure value object; instances are frozen in
 * {@link FrozenRecommendationRules}. Conditions are predicates over:
 * <ul>
 * <li><b>concept category</b> — proxied by an explicit concept-code set
 * (no concept category column exists in Phase 2R); an empty set means any
 * concept;</li>
 * <li><b>change type</b> — an explicit set of {@code ADDED}/{@code
 * REMOVED}/{@code MODIFIED}; an empty set means any change type;</li>
 * <li><b>personalized band</b> — the user-specific band of the triggering
 * concept item; an empty set means band-less (any band).</li>
 * </ul>
 *
 * <p>Assessment-level closure rules (REC-NONE-REQUIRED) are represented by a
 * descriptor with all-empty condition sets; they are never evaluated per
 * concept item — the engine owns their assessment-level semantics.
 *
 * @param ruleOrder declared evaluation order, 1-based, deterministic
 * @param ruleId stable rule identifier persisted on recommendations
 * @param conceptCodes concept-code set (empty = any concept)
 * @param changeTypes change-type set (empty = any change type)
 * @param personalizedBands personalized-band set (empty = band-less)
 * @param actionKind emitted action kind
 */
public record RecommendationRule(
		int ruleOrder,
		String ruleId,
		Set<String> conceptCodes,
		Set<String> changeTypes,
		Set<ImpactBand> personalizedBands,
		RecommendationActionKind actionKind) {

	public RecommendationRule {
		if (ruleOrder < 1) {
			throw new IllegalArgumentException("Rule order must be >= 1");
		}
		if (ruleId == null || ruleId.isBlank()) {
			throw new IllegalArgumentException("Rule id must not be blank");
		}
		if (conceptCodes == null || changeTypes == null || personalizedBands == null) {
			throw new IllegalArgumentException("Rule condition sets must not be null");
		}
		if (actionKind == null) {
			throw new IllegalArgumentException("Action kind must not be null");
		}
		ruleId = ruleId.trim();
		conceptCodes = Set.copyOf(conceptCodes);
		changeTypes = Set.copyOf(changeTypes);
		personalizedBands = Set.copyOf(personalizedBands);
	}
}
