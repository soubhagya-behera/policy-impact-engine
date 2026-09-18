-- Policy Impact Engine
-- V9: observation attempt recording (Phase 2S).
-- policy_fetch_attempt records every policy observation check: trigger,
-- status, HTTP status, bytes fetched, duration, error message, and attempt
-- number. It is history, not state: rows are never deleted, and concurrent
-- observations of one policy each record their own row (no claiming in 2S).
-- Ownership is derived through policy_id -> policy.id. There is no
-- duplicated user_id (V3/V4/V7/V8 no-duplicated-owner-FK convention).
-- Lifecycle mutability is explicitly sanctioned and narrow: only status,
-- completed_at, duration_ms, and error_message may transition, exactly once,
-- from PENDING/IN_PROGRESS to a terminal status. All payload columns
-- (policy_id, trigger, http_status, bytes_fetched, attempt_number,
-- started_at) are write-once. Scheduler state (next_check_at, claiming,
-- retry/backoff) is deliberately absent and belongs to a later phase.
-- bytes_fetched is an approximation (see PolicyFetchAttemptService); exact
-- wire-byte accounting is deferred.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version),
-- V3 (policy_change), V4 (privacy_concept/change_concept_match),
-- V5 (change_impact), V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown) or
-- V8 (recommendation).

CREATE TABLE policy_fetch_attempt (
    id              uuid        PRIMARY KEY,
    policy_id       uuid        NOT NULL REFERENCES policy (id),
    trigger         varchar(16) NOT NULL CHECK (trigger IN ('MANUAL', 'SCHEDULED')),
    status          varchar(32) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCESS', 'FAILED', 'SKIPPED_UNCHANGED')),
    http_status     integer     NULL CHECK (http_status BETWEEN 100 AND 599),
    bytes_fetched   bigint      NULL CHECK (bytes_fetched >= 0),
    duration_ms     bigint      NULL CHECK (duration_ms >= 0),
    error_message   text        NULL,
    attempt_number  integer     NOT NULL CHECK (attempt_number >= 1),
    started_at      timestamptz NOT NULL,
    completed_at    timestamptz NULL,
    CONSTRAINT chk_attempt_completed CHECK (
        (status IN ('SUCCESS', 'FAILED', 'SKIPPED_UNCHANGED') AND completed_at IS NOT NULL)
        OR (status IN ('PENDING', 'IN_PROGRESS'))
    )
);

-- Attempt history per policy, newest first.
CREATE INDEX idx_attempt_policy_started ON policy_fetch_attempt (policy_id, started_at DESC);
