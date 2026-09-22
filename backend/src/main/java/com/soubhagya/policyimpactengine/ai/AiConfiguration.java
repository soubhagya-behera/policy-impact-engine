package com.soubhagya.policyimpactengine.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 12 — wires the explanation provider (see DECISIONS.md
 * ADR-024 §§9–10, 13).
 *
 * <p>When AI is disabled (the safe default) the provider bean never
 * performs I/O: the service short-circuits before invoking it. When
 * enabled, malformed configuration fails fast here at startup
 * instead of degrading silently at request time.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {

	@Bean
	public AiExplanationProvider aiExplanationProvider(AiProperties properties) {
		if (properties == null || !properties.enabled()) {
			return request -> new ExplanationOutcome.Failure(FallbackReason.UNAVAILABLE);
		}
		if (!"ollama".equalsIgnoreCase(properties.provider())) {
			throw new IllegalStateException("Unsupported ai.provider: " + properties.provider());
		}
		if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
			throw new IllegalStateException("ai.base-url must be configured when ai is enabled");
		}
		if (properties.model() == null || properties.model().isBlank()) {
			throw new IllegalStateException("ai.model must be configured when ai is enabled");
		}
		if (!isPositive(properties.connectTimeout())) {
			throw new IllegalStateException("ai.connect-timeout must be positive when ai is enabled");
		}
		if (!isPositive(properties.requestTimeout())) {
			throw new IllegalStateException("ai.request-timeout must be positive when ai is enabled");
		}
		if (properties.maxInputChars() == null || properties.maxInputChars() <= 0) {
			throw new IllegalStateException("ai.max-input-chars must be positive when ai is enabled");
		}
		if (properties.maxOutputTokens() == null || properties.maxOutputTokens() <= 0) {
			throw new IllegalStateException("ai.max-output-tokens must be positive when ai is enabled");
		}
		return new OllamaExplanationProvider(properties.baseUrl(), properties.model(),
				properties.connectTimeout(), properties.requestTimeout(),
				properties.maxInputChars(), properties.maxOutputTokens());
	}

	private static boolean isPositive(java.time.Duration duration) {
		return duration != null && !duration.isNegative() && !duration.isZero();
	}
}
