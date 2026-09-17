-- Policy Impact Engine
-- V7: user-specific personalized impact assessments (Phase 2Q).
-- impact_assessment is one immutable snapshot per (user, policy transition);
-- impact_assessment_breakdown is one immutable row per ChangeImpact consumed.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version), V3 (policy_change),
-- V4 (privacy_concept/change_concept_match), V5 (change_impact) or V6 (app_user/user_privacy_preference).

CREATE TABLE impact_assessment (
    id                            uuid        PRIMARY KEY,
    user_id                       uuid        NOT NULL REFERENCES app_user (id),
    new_version_id                uuid        NOT NULL REFERENCES policy_version (id),
    previous_version_id           uuid        NOT NULL REFERENCES policy_version (id),
    aggregate_score               integer     NOT NULL CHECK (aggregate_score BETWEEN 0 AND 100),
    aggregate_band                varchar(16) NOT NULL CHECK (aggregate_band IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    personalization_rules_version integer     NOT NULL CHECK (personalization_rules_version >= 1),
    created_at                    timestamptz NOT NULL,
    CONSTRAINT chk_assessment_versions CHECK (previous_version_id <> new_version_id),
    CONSTRAINT uq_assessment_user_new_version UNIQUE (user_id, new_version_id)
);

CREATE INDEX idx_assessment_user ON impact_assessment (user_id);
CREATE INDEX idx_assessment_new_version ON impact_assessment (new_version_id);

CREATE TABLE impact_assessment_breakdown (
    id                            uuid        PRIMARY KEY,
    assessment_id                 uuid        NOT NULL REFERENCES impact_assessment (id),
    change_impact_id              uuid        NOT NULL REFERENCES change_impact (id),
    concept_code                  varchar(64) NOT NULL,
    change_type                   varchar(16) NOT NULL CHECK (change_type IN ('ADDED', 'REMOVED', 'MODIFIED')),
    system_normalized             integer     NOT NULL CHECK (system_normalized BETWEEN 0 AND 100),
    system_band                   varchar(16) NOT NULL CHECK (system_band IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    effective_sensitivity         smallint    NOT NULL CHECK (effective_sensitivity BETWEEN 0 AND 5),
    personalized_normalized       integer     NOT NULL CHECK (personalized_normalized BETWEEN 0 AND 100),
    personalized_band             varchar(16) NOT NULL CHECK (personalized_band IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    personalization_rules_version integer     NOT NULL CHECK (personalization_rules_version >= 1),
    created_at                    timestamptz NOT NULL,
    CONSTRAINT uq_breakdown_assessment_impact UNIQUE (assessment_id, change_impact_id)
);

CREATE INDEX idx_breakdown_assessment ON impact_assessment_breakdown (assessment_id);
CREATE INDEX idx_breakdown_impact ON impact_assessment_breakdown (change_impact_id);
