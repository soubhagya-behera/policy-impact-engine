package com.soubhagya.policyimpactengine.user;

/**
 * Phase 8B — single outward signal for every JWT validation failure
 * (bad signature, wrong algorithm, expired, malformed, missing or
 * non-UUID subject). Collapsing all causes into one type keeps token
 * internals out of error responses; it is never mapped to HTTP
 * directly — the filter clears the context and the existing entry
 * point emits the standard 401.
 */
public class JwtInvalidException extends RuntimeException {

	public JwtInvalidException(String message) {
		super(message);
	}
}
