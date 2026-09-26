package com.soubhagya.policyimpactengine.user;

/**
 * Phase 14-A/2b — single outward signal for every refresh-token rotation
 * failure (unknown digest, expired, revoked, reused, or concurrent loser).
 * One type and one fixed message keep token state out of error responses;
 * every cause maps to the same logical invalid-refresh-token condition.
 * HTTP mapping belongs to the later endpoint slice, not this one.
 */
public class InvalidRefreshTokenException extends RuntimeException {

	public InvalidRefreshTokenException(String message) {
		super(message);
	}
}
