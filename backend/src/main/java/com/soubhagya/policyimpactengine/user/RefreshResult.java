package com.soubhagya.policyimpactengine.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 14-A/2b — safe refresh-token issuance/rotation result. Carries the
 * owning user's id, the raw opaque token (returned to the caller exactly
 * once; only its SHA-256 digest is persisted), and its absolute lifetime.
 * Never a digest, hash, or any credential information.
 */
public record RefreshResult(

		UUID userId,
		String refreshToken,
		Instant expiresAt,
		long expiresInSeconds

) {
}
