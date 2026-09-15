-- Policy Impact Engine
-- V3: persisted policy changes for version transitions (Phase 2K).
-- Append-only: change records are never updated or deleted; corrections
-- arrive as new versions with new change records.
-- Non-destructive: does not alter V1 (policy) or V2 (policy_version).

CREATE TABLE policy_change (
    id                  uuid        PRIMARY KEY,
    previous_version_id uuid        NOT NULL REFERENCES policy_version (id),
    new_version_id      uuid        NOT NULL REFERENCES policy_version (id),
    change_type         varchar(16) NOT NULL CHECK (change_type IN ('ADDED', 'REMOVED', 'MODIFIED')),
    old_text            text,
    new_text            text,
    change_order        integer     NOT NULL CHECK (change_order >= 0),
    CONSTRAINT chk_policy_change_text_shape CHECK (
        (change_type = 'ADDED' AND old_text IS NULL AND new_text IS NOT NULL)
        OR (change_type = 'REMOVED' AND old_text IS NOT NULL AND new_text IS NULL)
        OR (change_type = 'MODIFIED' AND old_text IS NOT NULL AND new_text IS NOT NULL AND old_text <> new_text)
    )
);

-- One row per position in the deterministic document-ordered diff of a
-- single transition. Also the covering index for the only required
-- retrieval path: changes of one new version in document order.
ALTER TABLE policy_change
    ADD CONSTRAINT uq_policy_change_new_version_order UNIQUE (new_version_id, change_order);
