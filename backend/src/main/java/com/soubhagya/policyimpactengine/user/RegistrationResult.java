package com.soubhagya.policyimpactengine.user;

import java.util.UUID;

/**
 * Phase 8A — safe registration result. Carries the new user's id and
 * normalized email only; never a password or password hash.
 */
public record RegistrationResult(

		UUID id,
		String email

) {
}
