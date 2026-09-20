package com.soubhagya.policyimpactengine.user;

/**
 * Phase 8B — missing or foreign principal when a web layer helper
 * requires the authenticated user. Mapped to HTTP 401 through
 * {@code GlobalExceptionHandler} so controller-level authentication
 * failures share the filter-level 401 shape.
 */
public class AuthenticationRequiredException extends RuntimeException {

	public AuthenticationRequiredException(String message) {
		super(message);
	}
}
