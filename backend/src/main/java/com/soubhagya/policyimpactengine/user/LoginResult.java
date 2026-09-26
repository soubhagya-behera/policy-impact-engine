package com.soubhagya.policyimpactengine.user;

import java.util.UUID;

/**
 * Phase 8B — safe login result. Carries the authenticated user's id,
 * the issued access token, and its lifetime only; never a password,
 * hash, or any credential information.
 *
 * <p>Phase 14-A/3a adds the initial refresh token and its lifetime
 * (see DECISIONS.md ADR-029): the raw value is returned to the caller
 * exactly once; only its SHA-256 digest is persisted.
 */
public record LoginResult(

		UUID userId,
		String accessToken,
		long expiresInSeconds,
		String refreshToken,
		long refreshExpiresInSeconds

) {
}
