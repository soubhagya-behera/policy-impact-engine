package com.soubhagya.policyimpactengine.policy.fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 *
 * <p><b>Response-size limit:</b> the response body is bounded by
 * {@link #MAX_RESPONSE_BODY_BYTES} <b>bytes</b> of the raw response body,
 * counted before decoding to {@link String}. Multibyte UTF-8 content is
 * therefore counted by its encoded byte length, not by Java character
 * count. The limit is enforced in two layers: (1) a {@code Content-Length}
 * pre-check rejects oversized responses before the body is consumed, and
 * (2) the body is streamed via {@code BodyHandlers.ofInputStream()} with a
 * bounded read so chunked or length-omitted responses can never cause
 * unbounded allocation. Oversized responses fail with
 * {@link PolicyFetchException}; partial content is never returned.
 */
@Component
public class HttpPolicyFetcher implements PolicyFetcher {

	/**
	 * Maximum accepted response-body size in bytes (1 MiB).
	 *
	 * <p>Chosen for the policy-fetch use case: real-world policy HTML pages
	 * are typically tens to low hundreds of kilobytes, so 1 MiB leaves ample
	 * headroom while bounding per-fetch memory. Counted on raw body bytes
	 * before UTF-8 decoding, so multibyte characters count by their encoded
	 * size. Kept as a code-level constant for this slice; easy to change and
	 * overridable via the test constructor below without an external
	 * configuration system.
	 */
	public static final long MAX_RESPONSE_BODY_BYTES = 1_048_576L;

	private static final int READ_BUFFER_SIZE = 8192;

	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient client;
	private final Duration requestTimeout;
	private final SsrfGuard ssrfGuard;
	private final long maxResponseBodyBytes;

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
		this(ssrfGuard, connectTimeout, requestTimeout, MAX_RESPONSE_BODY_BYTES);
	}

	/**
	 * Creates a fetcher with an explicit maximum body size in bytes.
	 * Used by tests to exercise boundary semantics with small limits
	 * without downloading production-sized payloads.
	 */
	public HttpPolicyFetcher(SsrfGuard ssrfGuard, Duration connectTimeout, Duration requestTimeout,
			long maxResponseBodyBytes) {
		if (ssrfGuard == null) {
			throw new IllegalArgumentException("SsrfGuard must not be null");
		}
		if (connectTimeout == null || requestTimeout == null) {
			throw new IllegalArgumentException("Timeouts must not be null");
		}
		if (maxResponseBodyBytes < 1) {
			throw new IllegalArgumentException("Maximum response body size must be positive");
		}
		this.ssrfGuard = ssrfGuard;
		this.client = HttpClient.newBuilder()
				.connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		this.requestTimeout = requestTimeout;
		this.maxResponseBodyBytes = maxResponseBodyBytes;
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

		HttpResponse<InputStream> response;
		try {
			// ofInputStream() returns once headers arrive; the body streams,
			// so no unbounded String is ever allocated before the size check.
			response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (IOException ex) {
			throw new PolicyFetchException("Failed to fetch URL: " + url + " — " + ex.getMessage(), null,
					ex, true);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PolicyFetchException("Fetch interrupted for URL: " + url, null, ex, true);
		}
		catch (IllegalArgumentException ex) {
			throw new PolicyFetchException("Invalid request for URL: " + url, ex);
		}

		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			closeQuietly(response.body());
			// Phase 2U.1 classification: 5xx and 429 are transient
			// (bounded backoff); every other non-2xx — including 4xx and
			// the 3xx never followed here — is permanent (fail fast).
			boolean transientFailure = (status >= 500 && status <= 599) || status == 429;
			throw new PolicyFetchException(
					"Fetch failed for URL: " + url + " with HTTP status " + status, status,
					transientFailure);
		}

		String contentType = response.headers()
				.firstValue("Content-Type")
				.orElse(null);

		// Layer 1: Content-Length pre-check — reject without consuming the body.
		// Never the only protection: servers may omit it or use chunked encoding.
		long contentLength = response.headers()
				.firstValueAsLong("Content-Length")
				.orElse(-1L);
		if (contentLength > maxResponseBodyBytes) {
			closeQuietly(response.body());
			throw new PolicyFetchException(
					"Response exceeded maximum allowed size: Content-Length " + contentLength
							+ " bytes exceeds maximum of " + maxResponseBodyBytes + " bytes for URL: " + url,
					status, false);
		}

		// Layer 2: bounded streaming read — enforces the limit for chunked or
		// length-omitted bodies. Counts raw bytes before UTF-8 decoding.
		String body = readBoundedBody(response.body(), url, status);

		return new FetchResult(trimmed, status, contentType, body);
	}

	/**
	 * Reads the response stream up to {@code maxResponseBodyBytes + 1} bytes.
	 * Fails with {@link PolicyFetchException} as soon as the limit is
	 * exceeded, without allocating the full oversized payload.
	 */
	private String readBoundedBody(InputStream bodyStream, String url, int status) {
		try (InputStream in = bodyStream) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[READ_BUFFER_SIZE];
			long total = 0;
			int read;
			while ((read = in.read(chunk)) != -1) {
				total += read;
				if (total > maxResponseBodyBytes) {
					throw new PolicyFetchException(
							"Response exceeded maximum allowed size: response body exceeds maximum of "
									+ maxResponseBodyBytes + " bytes for URL: " + url,
							status, false);
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toString(StandardCharsets.UTF_8);
		}
		catch (PolicyFetchException ex) {
			throw ex;
		}
		catch (IOException ex) {
			throw new PolicyFetchException("Failed to fetch URL: " + url + " — " + ex.getMessage(), status,
					ex, true);
		}
	}

	private static void closeQuietly(InputStream stream) {
		if (stream != null) {
			try {
				stream.close();
			}
			catch (IOException ignored) {
				// Best effort: the fetch already failed.
			}
		}
	}
}
