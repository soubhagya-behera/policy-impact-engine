package com.soubhagya.policyimpactengine.policy.fetch;

/**
 * Application-level abstraction for obtaining the raw HTTP response
 * of a registered policy URL.
 *
 * <p>Implementations obtain the response without interpreting it.
 * HTML parsing, extraction, normalization, hashing, versioning and
 * any pipeline stages belong to later phases.
 */
public interface PolicyFetcher {

	/**
	 * Fetches the content at the given URL.
	 *
	 * @param url absolute http or https URL (production callers should supply https)
	 * @return successful fetch result; never null
	 * @throws PolicyFetchException if the request fails, times out, or the
	 *                              response status is not 2xx
	 */
	FetchResult fetch(String url);
}
