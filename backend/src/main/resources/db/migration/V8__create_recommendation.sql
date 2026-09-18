-- Policy Impact Engine
-- V8: deterministic recommendations (Phase 2R).
-- recommendation is append-only: rows are never updated or deleted. The
-- current/pending recommendation set for a user is the set attached to the
-- user's latest impact assessment (deliberate Phase 2R interpretation of the
-- older "replace pending set" wording; see DECISIONS.md).
-- Ownership is derived through assessment_id -> impact_assessment.user_id.
-- There is no duplicated user_id on recommendation (V3/V4/V7 convention:
-- no duplicated owner FK on child rows).
-- Non-destructive: does not alter V1 (policy), V2 (policy_version),
-- V3 (policy_change), V4 (privacy_concept/change_concept_match),
-- V5 (change_impact), V6 (app_user/user_privacy_preference) or
-- V7 (impact_assessment/impact_assessment_breakdown).

CREATE TABLE recommendation (
    id                            uuid        PRIMARY KEY,
    assessment_id                 uuid        NOT NULL REFERENCES impact_assessment (id),
    rule_id                       varchar(64) NOT NULL,
    rule_order                    integer     NOT NULL CHECK (rule_order >= 1),
    action_kind                   varchar(64) NOT NULL CHECK (action_kind IN ('EXERCISE_DELETION', 'OPT_OUT_SHARING', 'REVIEW_SETTINGS', 'NONE_REQUIRED')),
    concept_code                  varchar(64) NULL,
    personalized_normalized       integer     NOT NULL CHECK (personalized_normalized BETWEEN 0 AND 100),
    personalized_band             varchar(16) NOT NULL CHECK (personalized_band IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    recommendation_rules_version  integer     NOT NULL CHECK (recommendation_rules_version >= 1),
    created_at                    timestamptz NOT NULL,
    CONSTRAINT chk_recommendation_concept_code CHECK (
        (action_kind = 'NONE_REQUIRED' AND concept_code IS NULL)
        OR (action_kind <> 'NONE_REQUIRED' AND concept_code IS NOT NULL)
    )
);

-- Concept-level dedup: at most one row per (assessment, action kind, concept).
-- This mirrors the engine's deduplication key (actionKind, conceptCode).
CREATE UNIQUE INDEX uq_recommendation_action_concept
    ON recommendation (assessment_id, action_kind, concept_code)
    WHERE concept_code IS NOT NULL;

-- Closure: at most one assessment-level NONE_REQUIRED row per assessment.
CREATE UNIQUE INDEX uq_recommendation_none_required
    ON recommendation (assessment_id)
    WHERE concept_code IS NULL;

CREATE INDEX idx_recommendation_assessment ON recommendation (assessment_id);
