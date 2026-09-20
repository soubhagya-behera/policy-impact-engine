package com.soubhagya.policyimpactengine.policy.fetch;

import lombok.Getter;

/**
 * Thrown when a policy fetch cannot be completed successfully.
 *
 * <p>Covers network failures, timeouts, and non-2xx HTTP responses.
 *
 * <p>Phase 2U.1 retry classification rides on two fields set at the throw
 * site, which is the only place that knows the failure kind:
 * {@code httpStatus} carries the HTTP status when a response was received
 * ({@code null} otherwise), and {@code transientFailure} marks failures
 * worth retrying on bounded backoff (timeouts, connection failures, HTTP
 * 5xx, HTTP 429, DNS resolution failures). Permanent failures (HTTP 4xx,
 * SSRF policy rejections, oversized responses, malformed URLs) leave the
 * flag {@code false} and fail fast. The legacy message-only constructors
 * default to permanent with no status so existing call sites keep their
 * meaning.
 */
@Getter
public class PolicyFetchException extends RuntimeException {

	private final Integer httpStatus;

	private final boolean transientFailure;

	public PolicyFetchException(String message) {
		this(message, null, null, false);
	}

	public PolicyFetchException(String message, Throwable cause) {
		this(message, null, cause, false);
	}

	public PolicyFetchException(String message, Integer httpStatus, boolean transientFailure) {
		this(message, httpStatus, null, transientFailure);
	}

	public PolicyFetchException(String message, Integer httpStatus, Throwable cause,
			boolean transientFailure) {
		super(message, cause);
		if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
			throw new IllegalArgumentException("HTTP status must be between 100 and 599");
		}
		this.httpStatus = httpStatus;
		this.transientFailure = transientFailure;
	}
}
