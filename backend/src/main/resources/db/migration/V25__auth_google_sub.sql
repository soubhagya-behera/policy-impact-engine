-- Policy Impact Engine
-- V25: Google OIDC identity anchor (Phase 18-B, see DECISIONS.md ADR-037).
-- Adds nullable google_sub (Google OIDC subject) to app_user. NULL for
-- email/password-only and pre-auth rows; set exactly once for Google-linked
-- accounts. Uniqueness enforced by a partial unique index so any number of
-- NULL rows coexist while every non-null subject is globally unique.
-- password_hash stays NULL for Google-only rows (they cannot password-login;
-- AuthLoginService already rejects null hashes uniformly).
-- Non-destructive: does not alter V1-V24 objects or data.

ALTER TABLE app_user
ADD COLUMN google_sub VARCHAR(255) NULL;

CREATE UNIQUE INDEX uq_app_user_google_sub
    ON app_user (google_sub)
    WHERE google_sub IS NOT NULL;
