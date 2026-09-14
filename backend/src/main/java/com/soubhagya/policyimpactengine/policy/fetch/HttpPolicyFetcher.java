package com.soubhagya.policyimpactengine.policy.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * Basic HTTP fetcher using Java's standard {@link HttpClient}.
 *
 * <p>Responsibility is limited to obtaining the HTTP response body and
 * status information for a given URL. It does not perform HTML parsing,
 * extraction, normalization, hashing, or versioning.
 *
 * <p><b>SSRF limitation:</b> this foundational implementation does not
 * perform DNS-based private IP blocking, redirect revalidation, or
 * response-size limits. Production external fetching will require the
 * dedicated SSRF-hardening slice before it is considered safe.
 *
 * <p><b>Redirects:</b> redirects are not followed
 * ({@code HttpClient.Redirect.NEVER}). A 3xx response is treated as
 * a failure so that redirect targets cannot bypass future SSRF checks
 * silently. Enabling and securing redirects is deferred to the SSRF slice.
 */
@Component
public class HttpPolicyFetcher implements PolicyFetcher {

	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient client;
	private final Duration requestTimeout;

	public HttpPolicyFetcher() {
		this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
	}

	/**
	 * Creates a fetcher with explicit timeouts. Used by tests to exercise
	 * timeout behavior without waiting for conservative production defaults.
	 */
	public HttpPolicyFetcher(Duration connectTimeout, Duration requestTimeout) {
		if (connectTimeout == null || requestTimeout == null) {
			throw new IllegalArgumentException("Timeouts must not be null");
		}
		this.client = HttpClient.newBuilder()
				.connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		this.requestTimeout = requestTimeout;
	}

	@Override
	public FetchResult fetch(String url) {
		if (url == null || url.isBlank()) {
			throw new PolicyFetchException("URL must not be blank");
		}
		URI uri;
		try {
			uri = URI.create(url.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new PolicyFetchException("Invalid URL: " + url, ex);
		}

		HttpRequest request = HttpRequest.newBuilder(uri)
				.GET()
				.timeout(requestTimeout)
				.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
				.header("User-Agent", "PolicyImpactEngine/1.0")
				.build();

		HttpResponse<String> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new PolicyFetchException("Failed to fetch URL: " + url + " — " + ex.getMessage(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PolicyFetchException("Fetch interrupted for URL: " + url, ex);
		}
		catch (IllegalArgumentException ex) {
			throw new PolicyFetchException("Invalid request for URL: " + url, ex);
		}

		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			throw new PolicyFetchException(
					"Fetch failed for URL: " + url + " with HTTP status " + status);
		}

		String contentType = response.headers()
				.firstValue("Content-Type")
				.orElse(null);

		String body = response.body();
		if (body == null) {
			body = "";
		}

		return new FetchResult(url.trim(), status, contentType, body);
	}
}
