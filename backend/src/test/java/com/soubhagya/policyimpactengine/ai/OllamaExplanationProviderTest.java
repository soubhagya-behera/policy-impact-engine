package com.soubhagya.policyimpactengine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Phase 12 — provider tests against a local in-process {@link
 * HttpServer} stub. No real Ollama, no network: success, transport
 * failures, malformed/empty/oversized responses, and the input
 * budget (which must prevent any HTTP call) are all covered.
 */
class OllamaExplanationProviderTest {

	private static final UUID ASSESSMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private HttpServer server;
	private final AtomicInteger hits = new AtomicInteger();
	private final AtomicReference<String> lastBody = new AtomicReference<>("");
	private volatile int status = 200;
	private volatile String responseBody =
			"{\"message\":{\"role\":\"assistant\",\"content\":\"Plain explanation.\"},\"done\":true}";
	private volatile long delayMillis;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/api/chat", exchange -> {
			hits.incrementAndGet();
			byte[] request = exchange.getRequestBody().readAllBytes();
			lastBody.set(new String(request, StandardCharsets.UTF_8));
			if (delayMillis > 0) {
				try {
					Thread.sleep(delayMillis);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, payload.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(payload);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void successReturnsTextWithModelAndWellFormedRequest() {
		OllamaExplanationProvider provider = provider(4000, 300);

		ExplanationOutcome outcome = provider.explain(sample());

		assertThat(outcome).isEqualTo(
				new ExplanationOutcome.Success("Plain explanation.", "stub-model"));
		assertThat(hits.get()).isEqualTo(1);
		assertThat(lastBody.get()).contains("\"model\":\"stub-model\"");
		assertThat(lastBody.get()).contains("\"stream\":false");
		assertThat(lastBody.get()).contains("\"temperature\":0");
		assertThat(lastBody.get()).contains("\"num_predict\":300");
		assertThat(lastBody.get()).contains("Explain only these facts.");
	}

	@Test
	void connectionRefusedMapsToUnavailable() throws IOException {
		int freePort;
		try (ServerSocket socket = new ServerSocket(0)) {
			freePort = socket.getLocalPort();
		}
		OllamaExplanationProvider provider = new OllamaExplanationProvider(
				"http://localhost:" + freePort, "stub-model", Duration.ofSeconds(2),
				Duration.ofSeconds(5), 4000, 300);

		assertThat(provider.explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.UNAVAILABLE));
	}

	@Test
	void slowModelMapsToTimeout() {
		delayMillis = 1500;
		OllamaExplanationProvider provider = new OllamaExplanationProvider(baseUrl(),
				"stub-model", Duration.ofSeconds(2), Duration.ofMillis(200), 4000, 300);

		assertThat(provider.explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.TIMEOUT));
	}

	@Test
	void non2xxMapsToUnavailable() {
		status = 500;
		responseBody = "internal error";

		assertThat(provider(4000, 300).explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.UNAVAILABLE));
	}

	@Test
	void malformedJsonMapsToMalformed() {
		responseBody = "not json{";

		assertThat(provider(4000, 300).explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.MALFORMED));
	}

	@Test
	void missingContentMapsToMalformed() {
		responseBody = "{\"done\":true}";

		assertThat(provider(4000, 300).explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.MALFORMED));
	}

	@Test
	void nonTextualContentMapsToMalformed() {
		responseBody = "{\"message\":{\"content\":42}}";

		assertThat(provider(4000, 300).explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.MALFORMED));
	}

	@Test
	void emptyContentMapsToEmpty() {
		responseBody = "{\"message\":{\"content\":\"   \"}}";

		assertThat(provider(4000, 300).explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.EMPTY));
	}

	@Test
	void oversizedContentMapsToMalformedWithoutLeak() {
		responseBody = "{\"message\":{\"content\":\"" + "x".repeat(5000) + "\"}}";

		assertThat(provider(4000, 10).explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.MALFORMED));
	}

	@Test
	void overBudgetPromptPreventsAnyHttpCall() {
		OllamaExplanationProvider provider = provider(10, 300);

		assertThat(provider.explain(sample()))
				.isEqualTo(new ExplanationOutcome.Failure(FallbackReason.OVER_BUDGET));
		assertThat(hits.get()).isZero();
	}

	@Test
	void invalidConstructionAndNullRequestFailFast() {
		assertThatThrownBy(() -> new OllamaExplanationProvider("  ", "m",
				Duration.ofSeconds(2), Duration.ofSeconds(5), 4000, 300))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OllamaExplanationProvider(baseUrl(), null,
				Duration.ofSeconds(2), Duration.ofSeconds(5), 4000, 300))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OllamaExplanationProvider(baseUrl(), "m",
				Duration.ZERO, Duration.ofSeconds(5), 4000, 300))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OllamaExplanationProvider(baseUrl(), "m",
				Duration.ofSeconds(2), Duration.ofSeconds(5), 0, 300))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> provider(4000, 300).explain(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ExplanationOutcome.Success("  ", "m"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ExplanationOutcome.Failure(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private OllamaExplanationProvider provider(int maxInputChars, int maxOutputTokens) {
		return new OllamaExplanationProvider(baseUrl(), "stub-model", Duration.ofSeconds(2),
				Duration.ofSeconds(5), maxInputChars, maxOutputTokens);
	}

	private String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	private static ExplanationRequest sample() {
		return new ExplanationRequest(ASSESSMENT, "Acme Policy", 2, 1, 80, "CRITICAL",
				1,
				List.of(new ExplanationRequest.FactItem("Location", "MODIFIED", 5, 80,
						"HIGH", 1)),
				List.of(new ExplanationRequest.RecommendationItem("REVIEW_SETTINGS",
						"LOCATION", "HIGH")));
	}
}
