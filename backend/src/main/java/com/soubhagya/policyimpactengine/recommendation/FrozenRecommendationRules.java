package com.soubhagya.policyimpactengine.recommendation;

import java.util.List;
import java.util.Set;

import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;

/**
 * Phase 2R — the complete, frozen, code-defined recommendation rule set
 * (approved Phase 2R v1 product decisions; NEW decisions, not previously
 * specified behavior).
 *
 * <p>Rules are versioned with {@link #RECOMMENDATION_RULES_VERSION} and live
 * in code only — they are never externalized to the database.
 *
 * <p><b>Concept category proxy.</b> {@code privacy_concept} has no category
 * column in Phase 2R; explicit concept-code sets stand in for "concept
 * category". No category column is added in this phase.
 *
 * <p><b>Band semantics (decision 7).</b> Concept-level rules 1–3 condition on
 * the user-specific {@code personalizedBand} of the triggering item. Only the
 * REC-NONE-REQUIRED closure rule conditions on the assessment-level
 * {@code aggregateBand}.
 *
 * <p><b>Rule 1 — REC-DELETION-RIGHTS-LOST</b> (order 1, band-less):
 * {@code DELETION_RIGHTS} + {@code REMOVED}/{@code MODIFIED} →
 * {@code EXERCISE_DELETION}. {@code ADDED} is ignored. The rule is
 * intentionally band-less. The current diff engine cannot determine whether a
 * {@code MODIFIED} clause strengthened or weakened the deletion right, so
 * REMOVED/MODIFIED is treated as the deterministic signal.
 *
 * <p><b>Rule 2 — REC-SHARING-OPT-OUT</b> (order 2): {@code
 * THIRD_PARTY_SHARING} or {@code ADVERTISING} with personalized band
 * MEDIUM/HIGH/CRITICAL → {@code OPT_OUT_SHARING}. {@code LOCATION} is
 * intentionally excluded from this rule (it can still receive
 * REC-REVIEW-SETTINGS through the catch-all). {@code ADVERTISING} is
 * intentionally mapped to OPT_OUT_SHARING for this v1 recommendation
 * vocabulary.
 *
 * <p><b>Rule 3 — REC-REVIEW-SETTINGS</b> (order 3): any concept with
 * personalized band MEDIUM/HIGH/CRITICAL → {@code REVIEW_SETTINGS}. This is
 * the generic actionable catch-all; LOW and NONE never produce it.
 *
 * <p><b>Rule 4 — REC-NONE-REQUIRED</b> (order 4, assessment-level closure):
 * if no rule 1–3 fired for the assessment and the aggregate band is NONE or
 * LOW, emit exactly one assessment-level {@code NONE_REQUIRED} recommendation
 * with {@code concept_code = NULL}. It means "no actionable recommendation
 * was generated for this assessment", not "the policy has no changes".
 */
public final class FrozenRecommendationRules {

	public static final int RECOMMENDATION_RULES_VERSION = 1;

	public static final String RULE_REC_DELETION_RIGHTS_LOST = "REC-DELETION-RIGHTS-LOST";
	public static final String RULE_REC_SHARING_OPT_OUT = "REC-SHARING-OPT-OUT";
	public static final String RULE_REC_REVIEW_SETTINGS = "REC-REVIEW-SETTINGS";
	public static final String RULE_REC_NONE_REQUIRED = "REC-NONE-REQUIRED";

	/** Phase 2R proxy for "concept category": explicit concept-code sets. */
	public static final Set<String> DELETION_RIGHTS_CONCEPTS = Set.of("DELETION_RIGHTS");
	public static final Set<String> SHARING_OPT_OUT_CONCEPTS = Set.of("THIRD_PARTY_SHARING", "ADVERTISING");
	public static final Set<String> ANY_CONCEPT = Set.of();
	public static final Set<String> ANY_CHANGE_TYPE = Set.of();
	public static final Set<String> REMOVED_OR_MODIFIED = Set.of("REMOVED", "MODIFIED");

	/** Personalized bands that are considered actionable (rules 2 and 3). */
	public static final Set<ImpactBand> ACTIONABLE_BANDS = Set.of(ImpactBand.MEDIUM, ImpactBand.HIGH, ImpactBand.CRITICAL);
	/** Band-less condition: any personalized band matches (rule 1). */
	public static final Set<ImpactBand> ANY_BAND = Set.of();

	/** Rule 1: DELETION_RIGHTS + REMOVED/MODIFIED, band-less. */
	public static final RecommendationRule DELETION_RIGHTS_LOST = new RecommendationRule(
			1, RULE_REC_DELETION_RIGHTS_LOST, DELETION_RIGHTS_CONCEPTS,
			REMOVED_OR_MODIFIED, ANY_BAND, RecommendationActionKind.EXERCISE_DELETION);

	/** Rule 2: THIRD_PARTY_SHARING/ADVERTISING + MEDIUM+. LOCATION excluded. */
	public static final RecommendationRule SHARING_OPT_OUT = new RecommendationRule(
			2, RULE_REC_SHARING_OPT_OUT, SHARING_OPT_OUT_CONCEPTS,
			ANY_CHANGE_TYPE, ACTIONABLE_BANDS, RecommendationActionKind.OPT_OUT_SHARING);

	/** Rule 3: any concept + MEDIUM+ (generic actionable catch-all). */
	public static final RecommendationRule REVIEW_SETTINGS = new RecommendationRule(
			3, RULE_REC_REVIEW_SETTINGS, ANY_CONCEPT,
			ANY_CHANGE_TYPE, ACTIONABLE_BANDS, RecommendationActionKind.REVIEW_SETTINGS);

	/**
	 * Rule 4: assessment-level closure. Never evaluated per concept item; the
	 * engine emits it exactly once when rules 1–3 produced nothing and the
	 * aggregate band is NONE or LOW.
	 */
	public static final RecommendationRule NONE_REQUIRED = new RecommendationRule(
			4, RULE_REC_NONE_REQUIRED, ANY_CONCEPT,
			ANY_CHANGE_TYPE, ANY_BAND, RecommendationActionKind.NONE_REQUIRED);

	/** Concept-level rules in declared evaluation order (rules 1–3). */
	public static final List<RecommendationRule> CONCEPT_RULES =
			List.of(DELETION_RIGHTS_LOST, SHARING_OPT_OUT, REVIEW_SETTINGS);

	/** The assessment-level closure rule (rule 4). */
	public static final RecommendationRule CLOSURE_RULE = NONE_REQUIRED;

	/** All four rules in declared order (rules 1–4), for documentation/tests. */
	public static final List<RecommendationRule> ALL_RULES_IN_DECLARED_ORDER =
			List.of(DELETION_RIGHTS_LOST, SHARING_OPT_OUT, REVIEW_SETTINGS, NONE_REQUIRED);

	private FrozenRecommendationRules() {
		// Frozen catalog; not instantiable.
	}
}
