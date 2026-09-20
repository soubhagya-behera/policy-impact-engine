package com.soubhagya.policyimpactengine.user.web;

import java.util.UUID;

/**
 * Phase 8B — the only trusted authenticated principal (see DECISIONS.md
 * ADR-018).
 *
 * <p>Carries the application User UUID resolved from the JWT subject.
 * Constructed exclusively by {@link JwtAuthenticationFilter}; never
 * from request parameters, headers, bodies, or path variables.
 */
public record AuthenticatedUser(

		UUID userId

) {

	public AuthenticatedUser {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
	}
}
