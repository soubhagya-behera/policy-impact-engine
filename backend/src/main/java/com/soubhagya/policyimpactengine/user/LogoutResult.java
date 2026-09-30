package com.soubhagya.policyimpactengine.user;

import java.util.UUID;

/**
 * Phase 15-A/2 — logout outcome (see DECISIONS.md ADR-031). Carries
 * whether the call actually transitioned refresh-token state and, only
 * then, the owning user's id for the post-commit audit witness.
 * Idempotent no-ops (unknown/expired/revoked/superseded digests,
 * zero-live families, lost races) carry no user id and emit nothing.
 */
public record LogoutResult(

		boolean revoked,
		UUID userId

) {

	public static LogoutResult noop() {
		return new LogoutResult(false, null);
	}

	public static LogoutResult revoked(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return new LogoutResult(true, userId);
	}
}
