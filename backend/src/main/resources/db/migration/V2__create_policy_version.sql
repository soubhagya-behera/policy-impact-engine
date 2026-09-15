-- Policy Impact Engine
-- V2: immutable policy version history (Phase 2G). Append-only: versions are
-- never updated or deleted; corrections produce new versions.
-- Non-destructive: does not alter the V1 policy table.

CREATE TABLE policy_version (
    id                 uuid        PRIMARY KEY,
    policy_id          uuid        NOT NULL REFERENCES policy (id),
    version_number     integer     NOT NULL CHECK (version_number >= 1),
    content_hash       varchar(64) NOT NULL,
    normalized_content text        NOT NULL,
    observed_at        timestamptz NOT NULL
);

-- At most one row per (policy, version number). This is the database-level
-- guard against duplicate version numbers under concurrent observations.
ALTER TABLE policy_version
    ADD CONSTRAINT uq_policy_version_policy_number UNIQUE (policy_id, version_number);

-- Latest-version lookup per policy: findTopByPolicy_IdOrderByVersionNumberDesc.
CREATE INDEX idx_policy_version_policy_number ON policy_version (policy_id, version_number DESC);
