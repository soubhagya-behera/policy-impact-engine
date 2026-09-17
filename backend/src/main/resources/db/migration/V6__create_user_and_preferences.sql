-- Policy Impact Engine
-- V6: minimal user and privacy preferences (Phase 2P, auth-deferred).
-- app_user is append-only; user_privacy_preference rows are mutable in
-- sensitivity only (update or delete to revert to the concept default).
-- Non-destructive: does not alter V1 (policy), V2 (policy_version), V3 (policy_change), V4 (privacy_concept/change_concept_match) or V5 (change_impact).

CREATE TABLE app_user (
    id         uuid        PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE user_privacy_preference (
    id          uuid     PRIMARY KEY,
    user_id     uuid     NOT NULL REFERENCES app_user (id),
    concept_id  uuid     NOT NULL REFERENCES privacy_concept (id),
    sensitivity smallint NOT NULL CHECK (sensitivity BETWEEN 0 AND 5),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT uq_user_privacy_preference UNIQUE (user_id, concept_id)
);

CREATE INDEX idx_user_privacy_preference_user ON user_privacy_preference (user_id);
CREATE INDEX idx_user_privacy_preference_concept ON user_privacy_preference (concept_id);
