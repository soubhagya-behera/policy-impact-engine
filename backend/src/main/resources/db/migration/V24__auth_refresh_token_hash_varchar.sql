-- Policy Impact Engine
-- V24: align the auth_refresh_token hash columns with the JPA mapping
-- (Phase 14-A/2a follow-up, see DECISIONS.md ADR-029).
-- V19 created token_hash and replaced_by_token_hash as CHAR(64), but
-- RefreshToken maps both with @Column(length = 64), which Hibernate maps to
-- VARCHAR(64). Under spring.jpa.hibernate.ddl-auto=validate that mismatch is
-- a hard startup failure: "found bpchar (Types#CHAR), but expecting
-- varchar(64) (Types#VARCHAR)". The schema is corrected here rather than the
-- entity, so no already-applied migration is edited and no mapping is
-- weakened with columnDefinition to silence a real type disagreement.
-- Both columns become varchar(64), preserving the existing 64-character
-- bound. No row is truncated, blank-padded, or re-interpreted: every stored
-- digest is exactly 64 lowercase hex characters, and the CHAR -> VARCHAR cast
-- only strips padding that this data never carries. NOT NULL, the
-- uq_auth_refresh_token_hash unique constraint, the
-- chk_auth_refresh_token_expiry check, and the
-- idx_auth_refresh_token_user_expiry index are preserved; PostgreSQL rebuilds
-- the index backing the unique constraint automatically.
-- Non-destructive: does not alter any other table, column, index, or data.

ALTER TABLE auth_refresh_token
    ALTER COLUMN token_hash TYPE varchar(64);

ALTER TABLE auth_refresh_token
    ALTER COLUMN replaced_by_token_hash TYPE varchar(64);
