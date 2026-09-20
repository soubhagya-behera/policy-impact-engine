package com.soubhagya.policyimpactengine.user;

/**
 * Phase 8A — duplicate registration signal for an already-registered email.
 *
 * <p>Mapped to HTTP 409 through {@code GlobalExceptionHandler} so a
 * repeated registration is an explicit conflict, never a silent
 * overwrite and never a 500.
 */
public class DuplicateEmailException extends RuntimeException {

	public DuplicateEmailException(String message) {
		super(message);
	}
}
