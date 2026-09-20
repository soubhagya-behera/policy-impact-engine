package com.soubhagya.policyimpactengine.user;

/**
 * Phase 8B — single outward signal for every login credential failure
 * (unknown email, null password hash, wrong password, out-of-policy
 * password length). One type and one fixed message keep email
 * existence and password state out of error responses; mapped to HTTP
 * 401 through {@code GlobalExceptionHandler}.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException(String message) {
		super(message);
	}
}
