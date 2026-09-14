package com.soubhagya.policyimpactengine.policy.fetch;

/**
 * Thrown when a policy fetch cannot be completed successfully.
 *
 * <p>Covers network failures, timeouts, and non-2xx HTTP responses.
 * Keep the hierarchy intentionally small for this foundational slice.
 */
public class PolicyFetchException extends RuntimeException {

	public PolicyFetchException(String message) {
		super(message);
	}

	public PolicyFetchException(String message, Throwable cause) {
		super(message, cause);
	}
}
