-- Policy Impact Engine
-- V5: system-level concept impact scoring (Phase 2O).
-- Append-only: change impacts are immutable history, one per ChangeConceptMatch.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version), V3 (policy_change) or V4 (privacy_concept/change_concept_match).

CREATE TABLE change_impact (
    id                      uuid        PRIMARY KEY,
    match_id                uuid        NOT NULL REFERENCES change_concept_match (id),
    concept_code            varchar(64) NOT NULL,
    change_type             varchar(16) NOT NULL CHECK (change_type IN ('ADDED', 'REMOVED', 'MODIFIED')),
    concept_weight          integer     NOT NULL CHECK (concept_weight >= 0),
    change_type_multiplier  numeric(3,2) NOT NULL CHECK (change_type_multiplier >= 0),
    base_score              numeric(8,2) NOT NULL CHECK (base_score >= 0),
    normalized_score        integer     NOT NULL CHECK (normalized_score >= 0 AND normalized_score <= 100),
    impact_band             varchar(16) NOT NULL CHECK (impact_band IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    rules_version           integer     NOT NULL CHECK (rules_version >= 1),
    created_at              timestamptz NOT NULL,
    CONSTRAINT uq_change_impact_match UNIQUE (match_id)
);

CREATE INDEX idx_change_impact_match ON change_impact (match_id);
