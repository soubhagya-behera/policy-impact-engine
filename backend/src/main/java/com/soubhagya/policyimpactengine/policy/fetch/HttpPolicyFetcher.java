package com.soubhagya.policyimpactengine.policy.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Basic HTTP fetcher using Java's standard {@link HttpClient} with
 * network-level SSRF protection.
 *
 * <p>Responsibility is limited to obtaining the HTTP response body and
 * status information for a given URL. It does not perform HTML parsing,
 * extraction, normalization, hashing, or versioning.
 *
 * <p><b>SSRF protection:</b> every request is validated via {@link SsrfGuard}
 * before connecting. The guard resolves the hostname and rejects any URL
 * whose DNS records contain loopback, private, link-local, unspecified,
 * multicast, or other non-public addresses (IPv4 and IPv6). See
 * {@link SsrfGuard} for the TOCTOU / DNS-rebinding limitation that remains
 * with Java's standard HttpClient.
 *
 * <p><b>Redirects:</b> redirects are not followed
 * ({@code HttpClient.Redirect.NEVER}). A 3xx response is treated as
 * a failure. Per-redirect SSRF revalidation will be added when redirects
 * are enabled in a later slice.
 */
@Component
public class HttpPolicyFetcher implements PolicyFetcher {

	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient client;
	private final Duration requestTimeout;
	private final SsrfGuard ssrfGuard;

	@Autowired
	public HttpPolicyFetcher(SsrfGuard ssrfGuard) {
		this(ssrfGuard, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
	}

	public HttpPolicyFetcher() {
		this(new SsrfGuard(), DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
	}

	/**
	 * Creates a fetcher with explicit timeouts. Used by tests to exercise
	 * timeout behavior without waiting for conservative production defaults.
	 * Uses a strict {@link SsrfGuard} with real DNS resolution.
	 */
	public HttpPolicyFetcher(Duration connectTimeout, Duration requestTimeout) {
		this(new SsrfGuard(), connectTimeout, requestTimeout);
	}

	public HttpPolicyFetcher(SsrfGuard ssrfGuard, Duration connectTimeout, Duration requestTimeout) {
		if (ssrfGuard == null) {
			throw new IllegalArgumentException("SsrfGuard must not be null");
		}
		if (connectTimeout == null || requestTimeout == null) {
			throw new IllegalArgumentException("Timeouts must not be null");
		}
		this.ssrfGuard = ssrfGuard;
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
		String trimmed = url.trim();
		ssrfGuard.validateUrl(trimmed);

		URI uri;
		try {
			uri = URI.create(trimmed);
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

		return new FetchResult(trimmed, status, contentType, body);
	}
}
