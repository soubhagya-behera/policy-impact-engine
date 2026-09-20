-- Policy Impact Engine
-- V14: in-app notifications, emission only (Phase 10A).
-- notification records that one personalized impact assessment produced
-- actionable impact for its user. Exactly one row per assessment
-- (UNIQUE on assessment_id is the idempotency guard: repeats and races
-- converge to the single row). Ownership is derived through
-- assessment_id -> impact_assessment.user_id. There is no duplicated
-- user_id on notification (V3/V4/V7/V8 convention: no duplicated owner
-- FK on child rows).
-- Lifecycle mutability is explicitly sanctioned and narrow: read_at moves
-- from NULL to a timestamp exactly once (mark-read); it is never moved
-- backwards or reset, enforced here and guarded in code.
-- The notification feed REST endpoints and automatic observation fan-out
-- belong to a later slice (Phase 10B); this migration carries no
-- delivery-channel, scheduling, or authentication state.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version),
-- V3 (policy_change), V4 (privacy_concept/change_concept_match),
-- V5 (change_impact), V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown),
-- V8 (recommendation), V9 (policy_fetch_attempt),
-- V10 (policy.next_check_at), V11 (the partial unique in-flight claim
-- index), V12 (failure_kind) or V13 (the partial stale-lookup index).

CREATE TABLE notification (
    id              uuid        PRIMARY KEY,
    assessment_id   uuid        NOT NULL REFERENCES impact_assessment (id),
    created_at      timestamptz NOT NULL,
    read_at         timestamptz NULL,
    CONSTRAINT chk_notification_read_at CHECK (
        read_at IS NULL OR read_at >= created_at
    )
);

-- Idempotency: at most one notification row per assessment.
CREATE UNIQUE INDEX uq_notification_assessment
    ON notification (assessment_id);
