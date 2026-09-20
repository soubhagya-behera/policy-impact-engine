-- Policy Impact Engine
-- V16: authentication credential transition (Phase 8A, see DECISIONS.md ADR-017).
-- Adds nullable email + password_hash to app_user only. NULL during the
-- transition so pre-auth rows keep working; registration requires both.
-- Email is application-normalized (trim + lowercase) with uniqueness
-- enforced here. No roles, status, lockout, token, or credential tables.
-- DB-level NOT NULL tightening belongs to Phase 8B.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version),
-- V3 (policy_change), V4 (privacy_concept/change_concept_match),
-- V5 (change_impact), V6 (app_user apart from this additive change,
-- user_privacy_preference), V7 (impact_assessment/
-- impact_assessment_breakdown), V8 (recommendation),
-- V9 (policy_fetch_attempt), V10 (policy.next_check_at), V11 (the
-- partial unique in-flight claim index), V12 (failure_kind),
-- V13 (the partial stale-lookup index), V14 (notification) or
-- V15 (policy.owner_id).

ALTER TABLE app_user
ADD COLUMN email VARCHAR(254) NULL;

ALTER TABLE app_user
ADD COLUMN password_hash VARCHAR(255) NULL;

-- Duplicate detection guard for normalized (trimmed, lowercased) emails.
CREATE UNIQUE INDEX uq_app_user_email
    ON app_user (email);
