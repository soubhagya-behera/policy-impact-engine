package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Focused tests for {@link HttpPolicyFetcher} using a deterministic local
 * {@link HttpServer}. No external network access, no Testcontainers.
 */
class HttpPolicyFetcherTest {

	private HttpServer server;
	private String baseUrl;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.setExecutor(Executors.newCachedThreadPool());

		server.createContext("/ok", new FixedResponseHandler(200, "text/html; charset=utf-8",
				"<html><body>Privacy Policy v1</body></html>"));
		server.createContext("/notfound", new FixedResponseHandler(404, "text/plain", "Not Found"));
		server.createContext("/error", new FixedResponseHandler(500, "text/plain", "Internal Error"));
		server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", baseUrlPlaceholder() + "/ok");
			exchange.sendResponseHeaders(301, -1);
			exchange.close();
		});
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
		int port = server.getAddress().getPort();
		baseUrl = "http://localhost:" + port;
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void successfulFetchReturnsBodyAndMetadata() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher();

		FetchResult result = fetcher.fetch(baseUrl + "/ok");

		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(result.body()).isEqualTo("<html><body>Privacy Policy v1</body></html>");
		assertThat(result.contentType()).contains("text/html");
		assertThat(result.url()).isEqualTo(baseUrl + "/ok");
	}

	@Test
	void http404IsTreatedAsFailure() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher();

		assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/notfound"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("404");
	}

	@Test
	void http500IsTreatedAsFailure() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher();

		assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/error"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("500");
	}

	@Test
	void redirectIsNotFollowedAndIsTreatedAsFailure() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher();

		assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/redirect"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("301");
	}

	@Test
	void timeoutIsHandledPredictably() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher(
				Duration.ofSeconds(1), Duration.ofMillis(300));

		assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/slow"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void unreachableHostIsHandledAsFailure() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher(
				Duration.ofMillis(500), Duration.ofMillis(500));

		// closed port — deterministic, no external DNS
		assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1:1/"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void blankUrlIsRejected() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher();

		assertThatThrownBy(() -> fetcher.fetch("   "))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("blank");
	}

	@Test
	void doesNotReturnEmptySuccessForFailedRequests() {
		HttpPolicyFetcher fetcher = new HttpPolicyFetcher();

		// Ensure 404 does not produce an empty FetchResult
		try {
			fetcher.fetch(baseUrl + "/notfound");
		}
		catch (PolicyFetchException ex) {
			assertThat(ex.getMessage()).contains("Fetch failed");
			return;
		}
		throw new AssertionError("Expected PolicyFetchException not thrown");
	}

	private String baseUrlPlaceholder() {
		// Used before baseUrl is assigned during context creation.
		// Substitute at request time via handler that rebuilds location.
		// For redirect test we set location after server start by using
		// dynamic header; simplest is to return baseUrl if available else "".
		return baseUrl != null ? baseUrl : "";
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
