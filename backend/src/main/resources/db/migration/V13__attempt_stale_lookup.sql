-- Policy Impact Engine
-- V13: stale IN_PROGRESS lookup (Phase 2U.2).
-- Supports the stale-recovery sweeper's candidate selection
-- (status = 'IN_PROGRESS' AND started_at <= :cutoff, oldest first).
-- Partial so the index stays tiny: only in-flight rows are indexed,
-- terminal history rows are excluded.
-- The recovery transition itself (IN_PROGRESS -> FAILED/TRANSIENT with a
-- backoff reschedule through policy.next_check_at) needs no schema
-- change: it reuses the sanctioned terminal transition and the Phase
-- 2U.1 retry machinery.
-- Non-destructive: does not alter V1 (policy table definition apart from
-- the V10 additive column), V2 (policy_version), V3 (policy_change),
-- V4 (privacy_concept/change_concept_match), V5 (change_impact),
-- V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown),
-- V8 (recommendation), V9 (policy_fetch_attempt table definition),
-- V10 (policy.next_check_at), V11 (the partial unique in-flight claim
-- index, which is untouched) or V12 (failure_kind, which is untouched).

CREATE INDEX idx_attempt_stale_inflight
    ON policy_fetch_attempt (started_at ASC)
    WHERE (status = 'IN_PROGRESS');
