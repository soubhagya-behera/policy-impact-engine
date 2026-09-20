-- Policy Impact Engine
-- V12: retry failure classification (Phase 2U.1).
-- policy_fetch_attempt gains a nullable failure_kind recording whether a
-- FAILED row ended a transient (retryable) or permanent (fail-fast) check.
-- Only FAILED rows carry a kind; all other rows leave it NULL. Pre-2U.1
-- FAILED rows keep NULL and are treated as streak-reset history (a new
-- failure after them starts a fresh retry chain at attempt_number = 1).
-- Retry timing itself is represented through policy.next_check_at (no
-- PENDING rows are ever held open across a backoff window), so no
-- scheduling state is added here.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version),
-- V3 (policy_change), V4 (privacy_concept/change_concept_match),
-- V5 (change_impact), V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown),
-- V8 (recommendation), V9 (policy_fetch_attempt table definition apart
-- from this additive column), V10 (policy.next_check_at) or
-- V11 (the partial unique in-flight claim index, which is untouched).

ALTER TABLE policy_fetch_attempt
    ADD COLUMN failure_kind varchar(16) NULL
    CHECK (failure_kind IN ('TRANSIENT', 'PERMANENT'));
