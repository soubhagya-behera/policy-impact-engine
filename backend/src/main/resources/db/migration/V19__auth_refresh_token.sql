-- Policy Impact Engine
-- V19: refresh-token persistence foundation (Phase 14-A/2a, see DECISIONS.md ADR-029).
-- auth_refresh_token stores ONLY SHA-256 hex digests of opaque refresh
-- tokens (token_hash, CHAR(64)); raw tokens never touch the database.
-- Each row is one issued token's validity window (created_at/expires_at)
-- owned by app_user. Rotation consumes a row exactly once by stamping
-- revoked_at plus the successor link (replaced_by_token_hash); reuse
-- containment revokes a user's live rows via revoked_at. Revoked rows are
-- retained until expiry so reuse stays detectable; the scheduled purge
-- removes expired rows only. No token-family table, no roles, no OAuth2,
-- no audit coupling in this slice.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version),
-- V3 (policy_change), V4 (privacy_concept/change_concept_match),
-- V5 (change_impact), V6 (app_user/user_privacy_preference),
-- V7 (impact_assessment/impact_assessment_breakdown),
-- V8 (recommendation), V9 (policy_fetch_attempt),
-- V10 (policy.next_check_at), V11 (the partial unique in-flight claim
-- index), V12 (failure_kind), V13 (the partial stale-lookup index),
-- V14 (notification), V15 (policy.owner_id), V16 (auth credentials),
-- V17 (audit_event) or V18 (feed read indexes).

CREATE TABLE auth_refresh_token (
    id                      uuid        PRIMARY KEY,
    user_id                 uuid        NOT NULL REFERENCES app_user (id),
    token_hash              char(64)    NOT NULL,
    created_at              timestamptz NOT NULL,
    expires_at              timestamptz NOT NULL,
    revoked_at              timestamptz NULL,
    replaced_by_token_hash  char(64)    NULL,
    CONSTRAINT chk_auth_refresh_token_expiry CHECK (expires_at > created_at)
);

-- Rotation serialization: at most one row per digest; concurrent
-- same-token consumers converge on the single row.
ALTER TABLE auth_refresh_token
    ADD CONSTRAINT uq_auth_refresh_token_hash UNIQUE (token_hash);

-- Family revocation + purge scans: one user's rows ordered by expiry.
CREATE INDEX idx_auth_refresh_token_user_expiry
    ON auth_refresh_token (user_id, expires_at);
