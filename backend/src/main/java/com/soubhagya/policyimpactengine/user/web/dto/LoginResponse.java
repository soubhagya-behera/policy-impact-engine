package com.soubhagya.policyimpactengine.user.web.dto;

import com.soubhagya.policyimpactengine.user.LoginResult;

/**
 * Phase 8B — login response. Carries the access token, its type, and
 * its lifetime only; never email, password, hash, or any credential
 * information.
 */
public record LoginResponse(

		String accessToken,
		String tokenType,
		long expiresIn

) {

	public static LoginResponse from(LoginResult result) {
		return new LoginResponse(result.accessToken(), "Bearer", result.expiresInSeconds());
	}
}
