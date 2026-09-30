package com.soubhagya.policyimpactengine.user;

import java.util.UUID;

/**
 * Phase 15-B/2 — reuse signal for refresh-token rotation (see DECISIONS.md
 * ADR-032).
 *
 * <p>Thrown after the rotation transaction commits when a superseded
 * (successor-linked), unexpired token presentation revokes at least one
 * live session of the owning user. Carries only the owning user id and
 * the revoked-live count for the post-commit audit witness — never the
 * raw token, digest, successor hash, JWT, secret, email, headers, or IP.
 * The type and message stay byte-identical to
 * {@link InvalidRefreshTokenException}, so callers observe the same
 * uniform 401 with no oracle.
 */
public class RefreshReuseDetectedException extends InvalidRefreshTokenException {

	private final UUID userId;
	private final int revokedLiveCount;

	public RefreshReuseDetectedException(UUID userId, int revokedLiveCount) {
		super(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (revokedLiveCount < 1) {
			throw new IllegalArgumentException("Revoked live count must be positive");
		}
		this.userId = userId;
		this.revokedLiveCount = revokedLiveCount;
	}

	/**
	 * Owning user derived from the superseded row — the audit actor.
	 */
	public UUID getUserId() {
		return userId;
	}

	/**
	 * Live rows the family revocation actually revoked. Always positive:
	 * zero-live kills use the normal invalid signal with no audit.
	 */
	public int getRevokedLiveCount() {
		return revokedLiveCount;
	}
}
