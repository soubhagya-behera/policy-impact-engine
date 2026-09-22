package com.soubhagya.policyimpactengine.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 12 — local Ollama explanation provider over HTTP (see
 * DECISIONS.md ADR-024 §§9–10).
 *
 * <p>Spring-free. Uses the project's JDK {@link HttpClient} approach
 * (no new dependency) against {@code POST {baseUrl}/api/chat} with
 * {@code stream:false} and temperature 0. Every bound is explicit:
 * connect timeout, request timeout, pre-call input budget, and a
 * response-size safety cap. Transport and parsing problems surface
 * only as typed {@link ExplanationOutcome.Failure} reasons; raw
 * provider bodies are never logged or returned.
 */
public final class OllamaExplanationProvider implements AiExplanationProvider {

	/** Fixed instruction framing every model call. */
	static final String SYSTEM_PROMPT =
			"You explain persisted privacy-policy impact assessments in plain language.";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String baseUrl;
	private final String model;
	private final Duration requestTimeout;
	private final int maxInputChars;
	private final int maxOutputTokens;
	private final HttpClient client;

	public OllamaExplanationProvider(String baseUrl, String model, Duration connectTimeout,
			Duration requestTimeout, int maxInputChars, int maxOutputTokens) {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalArgumentException("Base URL must not be blank");
		}
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("Model must not be blank");
		}
		if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
			throw new IllegalArgumentException("Connect timeout must be positive");
		}
		if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
			throw new IllegalArgumentException("Request timeout must be positive");
		}
		if (maxInputChars <= 0) {
			throw new IllegalArgumentException("Max input chars must be positive");
		}
		if (maxOutputTokens <= 0) {
			throw new IllegalArgumentException("Max output tokens must be positive");
		}
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.model = model;
		this.requestTimeout = requestTimeout;
		this.maxInputChars = maxInputChars;
		this.maxOutputTokens = maxOutputTokens;
		this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
	}

	@Override
	public ExplanationOutcome explain(ExplanationRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("Request must not be null");
		}
		String prompt = request.promptText();
		if (prompt.length() > maxInputChars) {
			return new ExplanationOutcome.Failure(FallbackReason.OVER_BUDGET);
		}
		String body;
		try {
			body = MAPPER.writeValueAsString(Map.of(
					"model", model,
					"stream", false,
					"messages", List.of(
							Map.of("role", "system", "content", SYSTEM_PROMPT),
							Map.of("role", "user", "content", prompt)),
					"options", Map.of("temperature", 0, "num_predict", maxOutputTokens)));
		}
		catch (RuntimeException serializationFailure) {
			return new ExplanationOutcome.Failure(FallbackReason.MALFORMED);
		}
		HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/api/chat"))
				.timeout(requestTimeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response;
		try {
			response = client.send(httpRequest,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (HttpConnectTimeoutException connectTimeout) {
			return new ExplanationOutcome.Failure(FallbackReason.UNAVAILABLE);
		}
		catch (HttpTimeoutException timeout) {
			return new ExplanationOutcome.Failure(FallbackReason.TIMEOUT);
		}
		catch (IOException unreachable) {
			return new ExplanationOutcome.Failure(FallbackReason.UNAVAILABLE);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return new ExplanationOutcome.Failure(FallbackReason.UNAVAILABLE);
		}
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			return new ExplanationOutcome.Failure(FallbackReason.UNAVAILABLE);
		}
		return extractContent(response.body());
	}

	private ExplanationOutcome extractContent(String responseBody) {
		JsonNode content;
		try {
			JsonNode root = MAPPER.readTree(responseBody);
			JsonNode message = root == null ? null : root.get("message");
			content = message == null ? null : message.get("content");
		}
		catch (RuntimeException malformed) {
			return new ExplanationOutcome.Failure(FallbackReason.MALFORMED);
		}
		if (content == null || content.isNull() || content.isMissingNode()
				|| !content.isTextual()) {
			return new ExplanationOutcome.Failure(FallbackReason.MALFORMED);
		}
		String text = content.asText();
		if (text.isBlank()) {
			return new ExplanationOutcome.Failure(FallbackReason.EMPTY);
		}
		if (text.length() > responseCap()) {
			return new ExplanationOutcome.Failure(FallbackReason.MALFORMED);
		}
		return new ExplanationOutcome.Success(text, model);
	}

	private int responseCap() {
		return Math.max(4096, maxOutputTokens * 8);
	}
}
