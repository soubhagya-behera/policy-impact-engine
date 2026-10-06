-- Policy Impact Engine
-- V26: one-time Google completion codes (Phase 18-B, see DECISIONS.md ADR-037).
-- auth_google_completion holds short-lived single-use opaque codes bridging
-- the backend OAuth callback (a top-level navigation) to the SPA callback
-- page. Only the SHA-256 hex digest is persisted; the raw code is returned
-- to the callback redirect exactly once and never logged. Our access and
-- refresh tokens never appear in URLs. Rows are consumed atomically on
-- POST /api/v1/auth/google/complete; expired rows are inert.
-- Non-destructive: does not alter V1-V25 objects or data.

CREATE TABLE auth_google_completion (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES app_user (id),
    code_hash       varchar(64) NOT NULL,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    consumed_at     timestamptz NULL,
    CONSTRAINT chk_auth_google_completion_expiry CHECK (expires_at > created_at)
);

ALTER TABLE auth_google_completion
    ADD CONSTRAINT uq_auth_google_completion_hash UNIQUE (code_hash);

CREATE INDEX idx_auth_google_completion_user_expiry
    ON auth_google_completion (user_id, expires_at);
