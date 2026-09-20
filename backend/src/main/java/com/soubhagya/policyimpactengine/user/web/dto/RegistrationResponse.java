package com.soubhagya.policyimpactengine.user.web.dto;

import java.util.UUID;

import com.soubhagya.policyimpactengine.user.RegistrationResult;

/**
 * Phase 8A — account registration response. Carries the new user's id
 * and email only; never a password or password hash.
 */
public record RegistrationResponse(

		UUID id,
		String email

) {

	public static RegistrationResponse from(RegistrationResult result) {
		return new RegistrationResponse(result.id(), result.email());
	}
}
