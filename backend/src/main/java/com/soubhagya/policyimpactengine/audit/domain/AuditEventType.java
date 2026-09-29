package com.soubhagya.policyimpactengine.audit.domain;

/**
 * Phase 11A — frozen first-wave audit event catalog (see DECISIONS.md
 * ADR-023 §12).
 *
 * <p>Exactly these seven codes may be persisted; the {@code event_type}
 * CHECK in V17 (widened by V20, see DECISIONS.md ADR-030) enforces the
 * same set in the database. No login failure, read, observation,
 * version, assessment, recommendation, or notification codes exist yet
 * — later slices add codes only with an explicit decision. Persisted
 * as the enum name.
 */
public enum AuditEventType {
	AUTH_USER_REGISTERED,
	AUTH_LOGIN_SUCCEEDED,
	POLICY_REGISTERED,
	POLICY_OWNER_ASSIGNED,
	PRIVACY_PREFERENCE_UPSERTED,
	PRIVACY_PREFERENCE_DELETED,
	/**
	 * Phase 14-C/1 — catalog foundation only (see DECISIONS.md
	 * ADR-030): emitted by the later archive-transition slice on the
	 * actual ACTIVE-to-ARCHIVED transition, post-commit
	 * best-effort, never on idempotent repeats or reads. No emission
	 * wiring exists yet.
	 */
	POLICY_ARCHIVED
}
