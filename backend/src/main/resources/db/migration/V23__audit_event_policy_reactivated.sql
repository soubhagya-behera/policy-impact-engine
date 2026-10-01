-- Policy Impact Engine
-- V23: widen the frozen audit event catalog for reactivation
-- (Phase 16-A/2, see DECISIONS.md ADR-033).
-- Drops the V17 event_type CHECK constraint by its verified real
-- PostgreSQL name (audit_event_event_type_check — the auto-name for the
-- inline column CHECK, confirmed against postgres:16 rather than assumed)
-- and recreates it with exactly the existing ten codes plus
-- POLICY_REACTIVATED. No table, column, index, or data change; the CHECK
-- revalidation scans only the small audit_event table.
-- Non-destructive: does not alter V1–V22 objects or data.

ALTER TABLE audit_event DROP CONSTRAINT audit_event_event_type_check;

ALTER TABLE audit_event
    ADD CONSTRAINT audit_event_event_type_check CHECK (event_type IN (
        'AUTH_USER_REGISTERED',
        'AUTH_LOGIN_SUCCEEDED',
        'POLICY_REGISTERED',
        'POLICY_OWNER_ASSIGNED',
        'PRIVACY_PREFERENCE_UPSERTED',
        'PRIVACY_PREFERENCE_DELETED',
        'POLICY_ARCHIVED',
        'AUTH_LOGOUT_SUCCEEDED',
        'AUTH_LOGOUT_ALL_SUCCEEDED',
        'AUTH_REFRESH_REUSE_DETECTED',
        'POLICY_REACTIVATED'
    ));
