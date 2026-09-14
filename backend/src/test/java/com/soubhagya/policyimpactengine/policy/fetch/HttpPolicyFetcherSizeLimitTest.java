package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Deterministic response-size-limit tests for {@link HttpPolicyFetcher}.
 *
 * <p>Uses a local {@link HttpServer} only — no external network. A small
 * per-test limit keeps payloads tiny and fast while exercising the same
 * byte-counted streaming enforcement used in production
 * ({@link HttpPolicyFetcher#MAX_RESPONSE_BODY_BYTES}).
 */
class HttpPolicyFetcherSizeLimitTest {

	private static final long TEST_LIMIT = 64L;

	private HttpServer server;
	private String baseUrl;
	private final AtomicInteger contentLengthHits = new AtomicInteger();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.setExecutor(Executors.newCachedThreadPool());

		// Below limit: 5 bytes.
		server.createContext("/small", exchange -> sendBytes(exchange, "hello".getBytes(StandardCharsets.UTF_8)));
		// Exactly at limit: TEST_LIMIT bytes.
		server.createContext("/exact",
				exchange -> sendBytes(exchange, repeat('a', (int) TEST_LIMIT).getBytes(StandardCharsets.UTF_8)));
		// Above limit: TEST_LIMIT + 1 bytes with Content-Length set automatically.
		server.createContext("/over",
				exchange -> sendBytes(exchange, repeat('a', (int) TEST_LIMIT + 1).getBytes(StandardCharsets.UTF_8)));
		// Content-Length greater than limit: proves header pre-check path.
		server.createContext("/too-big-content-length", exchange -> {
			contentLengthHits.incrementAndGet();
			sendBytes(exchange, repeat('b', (int) TEST_LIMIT + 100).getBytes(StandardCharsets.UTF_8));
		});
		// Chunked (no Content-Length): forces the streaming enforcement layer.
		server.createContext("/chunked-over", exchange -> {
			byte[] bytes = repeat('c', (int) TEST_LIMIT + 100).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
			exchange.sendResponseHeaders(200, 0);
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
	void responseBelowLimitSucceeds() {
		HttpPolicyFetcher fetcher = fetcherWithLimit(TEST_LIMIT);

		FetchResult result = fetcher.fetch(baseUrl + "/small");

		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(result.body()).isEqualTo("hello");
	}

	@Test
	void responseExactlyAtLimitSucceeds() {
		HttpPolicyFetcher fetcher = fetcherWithLimit(TEST_LIMIT);

		FetchResult result = fetcher.fetch(baseUrl + "/exact");

		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(result.body().getBytes(StandardCharsets.UTF_8)).hasSize((int) TEST_LIMIT);
	}

	@Test
	void responseAboveLimitFails() {
		HttpPolicyFetcher fetcher = fetcherWithLimit(TEST_LIMIT);

		assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/over"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("maximum");
	}

	@Test
	void contentLengthGreaterThanLimitIsRejected() {
		HttpPolicyFetcher fetcher = fetcherWithLimit(TEST_LIMIT);

		// Implementation checks Content-Length headers before consuming the
		// body, so this path rejects without requiring the full body read.
		assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/too-big-content-length"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("Content-Length")
				.hasMessageContaining("maximum");
		assertThat(contentLengthHits.get()).isEqualTo(1);
	}

	@Test
	void chunkedResponseExceedingLimitIsRejected() {
		HttpPolicyFetcher fetcher = fetcherWithLimit(TEST_LIMIT);

		assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/chunked-over"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("maximum");
	}

	@Test
	void multibyteContentIsCountedByBytesNotCharacters() {
		// "€" is 3 bytes in UTF-8. Limit of 10 bytes: 3 chars (9 bytes) fits,
		// 4 chars (12 bytes) exceeds even though char count (4) is below 10.
		long limit = 10L;
		server.createContext("/euro-ok", exchange -> sendBytes(exchange, "€€€".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/euro-over", exchange -> sendBytes(exchange, "€€€€".getBytes(StandardCharsets.UTF_8)));
		HttpPolicyFetcher fetcher = fetcherWithLimit(limit);

		FetchResult ok = fetcher.fetch(baseUrl + "/euro-ok");
		assertThat(ok.body()).isEqualTo("€€€");

		assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/euro-over"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("maximum");
	}

	@Test
	void oversizedResponseDoesNotReturnPartialContent() {
		HttpPolicyFetcher fetcher = fetcherWithLimit(TEST_LIMIT);

		try {
			fetcher.fetch(baseUrl + "/over");
		}
		catch (PolicyFetchException ex) {
			assertThat(ex.getMessage()).contains("maximum");
			return;
		}
		throw new AssertionError("Expected PolicyFetchException not thrown");
	}

	private HttpPolicyFetcher fetcherWithLimit(long limit) {
		return new HttpPolicyFetcher(permissiveGuard(), Duration.ofSeconds(5), Duration.ofSeconds(10), limit);
	}

	private static SsrfGuard permissiveGuard() {
		return new SsrfGuard(host -> new java.net.InetAddress[0]) {
			@Override
			public void validateUrl(String url) {
			}

			@Override
			public void validateHost(String host) {
			}

			@Override
			public void validateAddress(java.net.InetAddress address) {
			}
		};
	}

	private static void sendBytes(HttpExchange exchange, byte[] bytes) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static String repeat(char c, int count) {
		StringBuilder sb = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			sb.append(c);
		}
		return sb.toString();
	}
}
