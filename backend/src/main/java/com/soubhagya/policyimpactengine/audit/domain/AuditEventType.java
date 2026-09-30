package com.soubhagya.policyimpactengine.audit.domain;

/**
 * Phase 11A — frozen first-wave audit event catalog (see DECISIONS.md
 * ADR-023 §12).
 *
 * <p>Exactly these nine codes may be persisted; the {@code event_type}
 * CHECK in V17 (widened by V20, see DECISIONS.md ADR-030, then by V21,
 * see DECISIONS.md ADR-031) enforces the same set in the database. No
 * login failure, read, observation, version, assessment,
 * recommendation, notification, or reuse-detection codes exist yet
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
	POLICY_ARCHIVED,
	/**
	 * Phase 15-A/2 — single-session logout witness (see DECISIONS.md
	 * ADR-031): emitted post-commit best-effort only when the presented
	 * refresh token actually transitions live to revoked (actor =
	 * owning user, resource = {@code USER}/user id, empty metadata).
	 * Unknown, expired, revoked, and superseded presentations emit
	 * nothing, as do idempotent repeats.
	 */
	AUTH_LOGOUT_SUCCEEDED,
	/**
	 * Phase 15-A/2 — all-sessions logout witness (see DECISIONS.md
	 * ADR-031): emitted post-commit best-effort only when at least one
	 * live refresh session is revoked (actor = principal, resource =
	 * {@code USER}/user id, empty metadata). Zero-live calls emit
	 * nothing.
	 */
	AUTH_LOGOUT_ALL_SUCCEEDED
}
