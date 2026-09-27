package com.soubhagya.policyimpactengine.user.web.dto;

import com.soubhagya.policyimpactengine.user.RefreshResult;

/**
 * Phase 14-A/3b — refresh response. Carries the newly issued access token,
 * its type, and its lifetime plus the rotated refresh token and its
 * lifetime only; never a digest, hash, user id, or credential information.
 */
public record RefreshResponse(

		String accessToken,
		String tokenType,
		long expiresIn,
		String refreshToken,
		long refreshExpiresIn

) {

	public static RefreshResponse from(String accessToken, long accessExpiresInSeconds,
			RefreshResult rotated) {
		return new RefreshResponse(accessToken, "Bearer", accessExpiresInSeconds,
				rotated.refreshToken(), rotated.expiresInSeconds());
	}
}
