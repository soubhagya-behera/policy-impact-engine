-- Policy Impact Engine
-- V15: single policy owner (Phase 10B-1).
-- Adds the nullable owner FK on policy only. Existing rows keep
-- NULL (unowned) and continue to be observed normally but remain
-- silent (no assessment, recommendation, or notification).
-- Ownership is exactly one user per policy: no policy_user,
-- subscription, or watch table, and no many-to-many relationship.
-- Automatic fan-out notifies only the single owner.
-- Non-destructive: does not alter V1 (policy definition apart from
-- this additive column), V2 (policy_version), V3 (policy_change),
-- V4 (privacy_concept/change_concept_match), V5 (change_impact),
-- V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown),
-- V8 (recommendation), V9 (policy_fetch_attempt),
-- V10 (policy.next_check_at), V11 (the partial unique in-flight claim
-- index), V12 (failure_kind), V13 (the partial stale-lookup index)
-- or V14 (notification). No speculative indexes.

ALTER TABLE policy
ADD COLUMN owner_id UUID NULL REFERENCES app_user(id);
