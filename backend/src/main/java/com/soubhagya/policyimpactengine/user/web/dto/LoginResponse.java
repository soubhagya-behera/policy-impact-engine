package com.soubhagya.policyimpactengine.user.web.dto;

import com.soubhagya.policyimpactengine.user.LoginResult;

/**
 * Phase 8B — login response. Carries the access token, its type, and
 * its lifetime only; never email, password, hash, or any credential
 * information.
 *
 * <p>Phase 14-A/3a adds the initial refresh token and its lifetime
 * (see DECISIONS.md ADR-029); access-token fields are unchanged.
 */
public record LoginResponse(

		String accessToken,
		String tokenType,
		long expiresIn,
		String refreshToken,
		long refreshExpiresIn

) {

	public static LoginResponse from(LoginResult result) {
		return new LoginResponse(result.accessToken(), "Bearer", result.expiresInSeconds(),
				result.refreshToken(), result.refreshExpiresInSeconds());
	}
}
