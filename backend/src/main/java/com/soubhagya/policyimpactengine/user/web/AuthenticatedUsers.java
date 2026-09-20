package com.soubhagya.policyimpactengine.user.web;

import java.util.UUID;

import org.springframework.security.core.Authentication;

import com.soubhagya.policyimpactengine.user.AuthenticationRequiredException;

/**
 * Phase 8B — web-only extraction of the application User UUID from the
 * authenticated principal.
 *
 * <p>Future {@code /me/*} controllers obtain the user id exclusively
 * through this helper. Application and domain services keep their
 * existing explicit-UUID contracts and never touch the security
 * context; there is no static current-user state.
 */
public final class AuthenticatedUsers {

	private AuthenticatedUsers() {
	}

	/**
	 * Returns the authenticated user's id, or throws when no trusted
	 * {@link AuthenticatedUser} principal is present.
	 */
	public static UUID requireUserId(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
			throw new AuthenticationRequiredException("Authentication is required");
		}
		return principal.userId();
	}
}
