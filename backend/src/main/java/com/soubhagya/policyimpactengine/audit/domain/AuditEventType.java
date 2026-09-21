package com.soubhagya.policyimpactengine.audit.domain;

/**
 * Phase 11A — frozen first-wave audit event catalog (see DECISIONS.md
 * ADR-023 §12).
 *
 * <p>Exactly these six codes may be persisted; the {@code event_type}
 * CHECK in V17 enforces the same set in the database. No login
 * failure, read, observation, version, assessment, recommendation, or
 * notification codes exist yet — later slices add codes only with an
 * explicit decision. Persisted as the enum name.
 */
public enum AuditEventType {
	AUTH_USER_REGISTERED,
	AUTH_LOGIN_SUCCEEDED,
	POLICY_REGISTERED,
	POLICY_OWNER_ASSIGNED,
	PRIVACY_PREFERENCE_UPSERTED,
	PRIVACY_PREFERENCE_DELETED
}
