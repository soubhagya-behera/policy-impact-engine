package com.soubhagya.policyimpactengine.monitoring.domain;

/**
 * Phase 2U.1 — classifies why a {@code FAILED} attempt failed.
 *
 * <p>{@code TRANSIENT} failures (timeouts, connection failures, HTTP 5xx,
 * HTTP 429, DNS resolution failures, persistence/concurrency failures) are
 * retried on bounded exponential backoff with jitter. {@code PERMANENT}
 * failures (HTTP 4xx, SSRF rejections, oversized responses, deterministic
 * pipeline failures) fail fast with no retry. Only {@code FAILED} rows
 * carry a kind; every other row — including pre-2U.1 {@code FAILED} rows,
 * which keep {@code NULL} — leaves it unset, and an unset kind resets the
 * retry chain.
 */
public enum PolicyFetchFailureKind {

	TRANSIENT,

	PERMANENT
}
