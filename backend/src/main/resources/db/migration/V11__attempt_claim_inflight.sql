-- Policy Impact Engine
-- V11: atomic work claiming (Phase 2U).
-- At most one non-terminal attempt (PENDING or IN_PROGRESS) may exist per
-- policy. This partial unique index is the database-enforced cross-instance
-- invariant behind the claim path: concurrent triggers for the same policy
-- race at PENDING-insert time and exactly one wins; the loser is rejected
-- (MANUAL) or skipped (SCHEDULED) without fetching.
-- Terminal rows (SUCCESS, FAILED, SKIPPED_UNCHANGED) are unaffected, so
-- attempt history stays append-only and concurrent checks for different
-- policies proceed independently.
-- Stale-attempt recovery is deliberately absent (Phase 2U.2); no reaper
-- index is created here.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version),
-- V3 (policy_change), V4 (privacy_concept/change_concept_match),
-- V5 (change_impact), V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown),
-- V8 (recommendation), V9 (policy_fetch_attempt table definition) or
-- V10 (policy.next_check_at).

CREATE UNIQUE INDEX uq_policy_fetch_attempt_inflight
    ON policy_fetch_attempt (policy_id)
    WHERE (status IN ('PENDING', 'IN_PROGRESS'));
