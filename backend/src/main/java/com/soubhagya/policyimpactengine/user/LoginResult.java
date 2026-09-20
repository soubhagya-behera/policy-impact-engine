package com.soubhagya.policyimpactengine.user;

import java.util.UUID;

/**
 * Phase 8B — safe login result. Carries the authenticated user's id,
 * the issued access token, and its lifetime only; never a password,
 * hash, or any credential information.
 */
public record LoginResult(

		UUID userId,
		String accessToken,
		long expiresInSeconds

) {
}
