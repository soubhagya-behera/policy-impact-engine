package com.soubhagya.policyimpactengine.user;

/**
 * Phase 18-B — Google identity rejection (see DECISIONS.md ADR-037).
 *
 * <p>Covers every fail-closed Google path: unverified or missing email,
 * invalid OIDC claims, local user-creation failure, or a completion-code
 * problem. Never carries token, code, secret, or provider detail.
 * Maps to HTTP 401 through the existing global handler.
 */
public class InvalidGoogleIdentityException extends RuntimeException {

	public InvalidGoogleIdentityException(String message) {
		super(message);
	}
}
