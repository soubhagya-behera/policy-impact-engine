package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Phase 2U.1 — failure classification tests for retry.
 *
 * <p>Pins the exact classification table: which fetch/guard failures are
 * transient (bounded backoff) and which are permanent (fail fast), plus
 * the HTTP status carried when a response was received. HTTP-layer cases
 * run against a deterministic local {@link HttpServer} with a permissive
 * guard; guard-layer cases use strict or mocked DNS resolution. No
 * external network access.
 */
class PolicyFetchClassificationTest {

	private HttpServer server;
	private String baseUrl;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/missing", new FixedResponseHandler(404, "text/plain", "Not Found"));
		server.createContext("/broken", new FixedResponseHandler(500, "text/plain", "Internal Error"));
		server.createContext("/limited", new FixedResponseHandler(429, "text/plain", "Slow Down"));
		server.createContext("/moved", exchange -> {
			exchange.getResponseHeaders().add("Location", baseUrl + "/missing");
			exchange.sendResponseHeaders(301, -1);
			exchange.close();
		});
		server.createContext("/ok", new FixedResponseHandler(200, "text/html; charset=utf-8",
				"<html><body>Privacy Policy v1</body></html>"));
		server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(2000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] bytes = "slow body".getBytes();
			exchange.getResponseHeaders().add("Content-Type", "text/plain");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.start();
		baseUrl = "http://localhost:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void http404IsPermanentWithStatus() {
		PolicyFetchException failure = fetchFailure(baseUrl + "/missing");

		assertThat(failure.isTransientFailure()).isFalse();
		assertThat(failure.getHttpStatus()).isEqualTo(404);
	}

	@Test
	void http500IsTransientWithStatus() {
		PolicyFetchException failure = fetchFailure(baseUrl + "/broken");

		assertThat(failure.isTransientFailure()).isTrue();
		assertThat(failure.getHttpStatus()).isEqualTo(500);
	}

	@Test
	void http429IsTransientWithStatus() {
		PolicyFetchException failure = fetchFailure(baseUrl + "/limited");

		assertThat(failure.isTransientFailure()).isTrue();
		assertThat(failure.getHttpStatus()).isEqualTo(429);
	}

	@Test
	void redirectIsPermanentWithStatus() {
		PolicyFetchException failure = fetchFailure(baseUrl + "/moved");

		assertThat(failure.isTransientFailure()).isFalse();
		assertThat(failure.getHttpStatus()).isEqualTo(301);
	}

	@Test
	void timeoutIsTransientWithoutStatus() {
		HttpPolicyFetcher fetcher = permissiveFetcher(
				Duration.ofSeconds(1), Duration.ofMillis(300));

		PolicyFetchException failure = catchThrowableOfType(
				() -> fetcher.fetch(baseUrl + "/slow"), PolicyFetchException.class);

		assertThat(failure.isTransientFailure()).isTrue();
		assertThat(failure.getHttpStatus()).isNull();
	}

	@Test
	void connectionRefusedIsTransientWithoutStatus() {
		HttpPolicyFetcher fetcher = permissiveFetcher(
				Duration.ofMillis(500), Duration.ofMillis(500));

		PolicyFetchException failure = catchThrowableOfType(
				() -> fetcher.fetch("http://127.0.0.1:1/"), PolicyFetchException.class);

		assertThat(failure.isTransientFailure()).isTrue();
		assertThat(failure.getHttpStatus()).isNull();
	}

	@Test
	void oversizedResponseIsPermanentWithStatus() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher(permissiveGuard(),
				Duration.ofSeconds(5), Duration.ofSeconds(10), 10L);

		PolicyFetchException failure = catchThrowableOfType(
				() -> fetcher.fetch(baseUrl + "/ok"), PolicyFetchException.class);

		assertThat(failure.isTransientFailure()).isFalse();
		assertThat(failure.getHttpStatus()).isEqualTo(200);
	}

	@Test
	void blankUrlIsPermanentWithoutStatus() {
		PolicyFetchException failure = catchThrowableOfType(
				() -> new HttpPolicyFetcher().fetch("   "), PolicyFetchException.class);

		assertThat(failure.isTransientFailure()).isFalse();
		assertThat(failure.getHttpStatus()).isNull();
	}

	@Test
	void ssrfPolicyRejectionIsPermanent() {
		PolicyFetchException failure = catchThrowableOfType(
				() -> new HttpPolicyFetcher().fetch(baseUrl + "/ok"), PolicyFetchException.class);

		assertThat(failure.isTransientFailure()).isFalse();
		assertThat(failure.getHttpStatus()).isNull();
	}

	@Test
	void dnsResolutionFailureIsTransient() {
		SsrfGuard guard = new SsrfGuard(host -> {
			throw new UnknownHostException("dns down");
		});

		PolicyFetchException failure = catchThrowableOfType(
				() -> guard.validateUrl("https://example.com/policy"), PolicyFetchException.class);

		assertThat(failure.isTransientFailure()).isTrue();
		assertThat(failure.getHttpStatus()).isNull();
	}

	@Test
	void emptyDnsAnswerIsTransient() {
		SsrfGuard guard = new SsrfGuard(host -> new java.net.InetAddress[0]);

		PolicyFetchException failure = catchThrowableOfType(
				() -> guard.validateUrl("https://example.com/policy"), PolicyFetchException.class);

		assertThat(failure.isTransientFailure()).isTrue();
	}

	@Test
	void legacyConstructorsDefaultToPermanentWithoutStatus() {
		assertThat(new PolicyFetchException("boom").isTransientFailure()).isFalse();
		assertThat(new PolicyFetchException("boom").getHttpStatus()).isNull();
		assertThat(new PolicyFetchException("boom", new IOException("down")).isTransientFailure())
				.isFalse();
		assertThat(new PolicyFetchException("boom", new IOException("down")).getHttpStatus()).isNull();
	}

	private PolicyFetchException fetchFailure(String url) {
		return catchThrowableOfType(
				() -> permissiveFetcher().fetch(url), PolicyFetchException.class);
	}

	private HttpPolicyFetcher permissiveFetcher() {
		return permissiveFetcher(Duration.ofSeconds(5), Duration.ofSeconds(10));
	}

	private HttpPolicyFetcher permissiveFetcher(Duration connectTimeout, Duration requestTimeout) {
		return new HttpPolicyFetcher(permissiveGuard(), connectTimeout, requestTimeout);
	}

	private static SsrfGuard permissiveGuard() {
		return new SsrfGuard(host -> new java.net.InetAddress[0]) {
			@Override
			public void validateUrl(String url) {
				// bypass SSRF for HTTP-layer isolation tests
			}

			@Override
			public void validateHost(String host) {
			}

			@Override
			public void validateAddress(java.net.InetAddress address) {
			}
		};
	}

	private static class FixedResponseHandler implements HttpHandler {
		private final int status;
		private final String contentType;
		private final String body;

		FixedResponseHandler(int status, String contentType, String body) {
			this.status = status;
			this.contentType = contentType;
			this.body = body;
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			byte[] bytes = body.getBytes();
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
	}
}
