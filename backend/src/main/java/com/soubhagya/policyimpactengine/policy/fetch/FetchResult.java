package com.soubhagya.policyimpactengine.policy.fetch;

/**
 * Result of a successful policy fetch.
 *
 * @param url         the requested URL (as supplied to the fetcher)
 * @param statusCode  HTTP status code (2xx only for this result type)
 * @param contentType value of the Content-Type header, may be null if absent
 * @param body        response body as a String; never null (may be empty)
 */
public record FetchResult(
		String url,
		int statusCode,
		String contentType,
		String body
) {
}
