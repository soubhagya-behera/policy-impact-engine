-- Policy Impact Engine
-- V10: scheduled-check bookkeeping (Phase 2T).
-- policy.next_check_at stores the next time a policy becomes eligible for
-- a scheduled observation. The scheduler selects ACTIVE policies with
-- next_check_at <= now in deterministic (next_check_at, id) order and
-- advances the timestamp by the configured interval after every completed
-- check (SUCCESS, SKIPPED_UNCHANGED, and FAILED alike; no backoff in 2T).
-- Existing rows are backfilled to now() so they are immediately eligible
-- for the first tick; no staggering in this phase. Retry/backoff/jitter,
-- work claiming, and stale-attempt reclamation are deliberately absent and
-- belong to the follow-up claiming/retry slice.
-- Non-destructive: does not alter V1 (policy table definition apart from
-- this additive column), V2 (policy_version), V3 (policy_change),
-- V4 (privacy_concept/change_concept_match), V5 (change_impact),
-- V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown),
-- V8 (recommendation) or V9 (policy_fetch_attempt).

ALTER TABLE policy ADD COLUMN next_check_at timestamptz NULL;

UPDATE policy SET next_check_at = now() WHERE next_check_at IS NULL;

ALTER TABLE policy ALTER COLUMN next_check_at SET NOT NULL;

-- Due-policy selection: ACTIVE policies whose next check time has elapsed.
CREATE INDEX idx_policy_due_check ON policy (status, next_check_at);
