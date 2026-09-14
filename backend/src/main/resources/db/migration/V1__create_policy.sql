-- Policy Impact Engine
-- V1: schema for policy registration (Phase 1). Only the policy table is created here;
-- tables for later phases (versions, sections, changes, concepts, users, ...) are added
-- by their own migrations.

CREATE TABLE policy (
    id         uuid          PRIMARY KEY,
    name       varchar(255)  NOT NULL,
    url        varchar(2048) NOT NULL,
    status     varchar(32)   NOT NULL,
    created_at timestamptz   NOT NULL,
    updated_at timestamptz   NOT NULL
);

-- The policy list is served filtered by status; keep that lookup indexed.
CREATE INDEX idx_policy_status ON policy (status);
