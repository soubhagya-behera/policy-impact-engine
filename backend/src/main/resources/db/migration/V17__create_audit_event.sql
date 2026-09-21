-- Policy Impact Engine
-- V17: append-only audit event foundation with cryptographic chain (Phase 11A).
-- audit_event records meaningful actor/system actions (authentication,
-- ownership assignment, preference changes) as immutable rows linked into
-- one global SHA-256 hash chain (see DECISIONS.md ADR-022/ADR-023).
-- Each non-genesis row references its predecessor through prev_hash; the
-- genesis row carries NULL. Chain linkage is guarded by uniqueness:
-- UNIQUE(prev_hash) allows at most one child per predecessor (this is the
-- cross-instance append serializer; PostgreSQL treats NULLs as distinct,
-- so twin geneses are blocked separately below), and UNIQUE(event_hash)
-- rejects duplicate-hash rows.
-- Reads are actor-scoped (actor_user_id, newest first); there is no
-- update/delete path, no retention purge, no REST surface, and no event
-- emission wiring in this slice (11B verification, 11C emission, 11D
-- read API belong to later slices).
-- Non-destructive: does not alter V1 (policy), V2 (policy_version),
-- V3 (policy_change), V4 (privacy_concept/change_concept_match),
-- V5 (change_impact), V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown),
-- V8 (recommendation), V9 (policy_fetch_attempt),
-- V10 (policy.next_check_at), V11 (the partial unique in-flight claim
-- index), V12 (failure_kind), V13 (the partial stale-lookup index),
-- V14 (notification), V15 (policy.owner_id) or V16 (auth credentials).

CREATE TABLE audit_event (
    id              uuid        PRIMARY KEY,
    occurred_at     timestamptz NOT NULL,
    actor_user_id   uuid        NULL REFERENCES app_user (id),
    event_type      varchar(32) NOT NULL CHECK (event_type IN (
        'AUTH_USER_REGISTERED',
        'AUTH_LOGIN_SUCCEEDED',
        'POLICY_REGISTERED',
        'POLICY_OWNER_ASSIGNED',
        'PRIVACY_PREFERENCE_UPSERTED',
        'PRIVACY_PREFERENCE_DELETED'
    )),
    resource_type   varchar(32) NULL,
    resource_id     uuid        NULL,
    metadata        TEXT        NULL,
    prev_hash       varchar(64) NULL CHECK (
        prev_hash IS NULL OR char_length(prev_hash) = 64
    ),
    event_hash      varchar(64) NOT NULL CHECK (
        char_length(event_hash) = 64
    ),
    CONSTRAINT uq_audit_event_prev_hash UNIQUE (prev_hash),
    CONSTRAINT uq_audit_event_hash UNIQUE (event_hash)
);

-- Genesis guard: plain UNIQUE(prev_hash) treats NULLs as distinct, so it
-- cannot prevent twin genesis rows. Exactly one row with NULL prev_hash
-- may ever exist; the application allows NULL only on an empty table.
CREATE UNIQUE INDEX uq_audit_event_single_genesis
    ON audit_event ((1))
    WHERE prev_hash IS NULL;

-- User feed: one actor's events, newest first.
CREATE INDEX idx_audit_event_actor_time
    ON audit_event (actor_user_id, occurred_at DESC, id DESC);
