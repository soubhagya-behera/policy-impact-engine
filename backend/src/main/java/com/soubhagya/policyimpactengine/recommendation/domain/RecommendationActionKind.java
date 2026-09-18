package com.soubhagya.policyimpactengine.recommendation.domain;

/**
 * Phase 2R — closed vocabulary of recommended user actions (v1).
 *
 * <p>Frozen for {@code RECOMMENDATION_RULES_VERSION = 1}:
 * <ul>
 * <li>{@link #EXERCISE_DELETION} — the user's deletion right is affected.</li>
 * <li>{@link #OPT_OUT_SHARING} — the user should opt out of sharing.</li>
 * <li>{@link #REVIEW_SETTINGS} — the user should review privacy settings.</li>
 * <li>{@link #NONE_REQUIRED} — assessment-level closure: no actionable
 * recommendation was generated for this assessment. It explicitly does
 * <b>not</b> mean "the policy has no changes". The only valid
 * {@code conceptCode} for this kind is {@code null}.</li>
 * </ul>
 */
public enum RecommendationActionKind {
	EXERCISE_DELETION,
	OPT_OUT_SHARING,
	REVIEW_SETTINGS,
	NONE_REQUIRED
}
