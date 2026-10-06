package com.soubhagya.policyimpactengine.user;

/**
 * Phase 18-B — validated Google OIDC identity (see DECISIONS.md ADR-037).
 *
 * <p>Carries only the stable provider subject plus the normalized verified
 * email Spring's OIDC validation already approved. Never a raw ID token,
 * authorization code, or access token.
 */
public record GoogleIdentity(

		String subject,
		String email,
		boolean emailVerified

) {

	public GoogleIdentity {
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("Google subject must not be blank");
		}
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("Google email must not be blank");
		}
	}
}
