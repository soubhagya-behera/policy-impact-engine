package com.soubhagya.policyimpactengine.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 12 — AI configuration bound from {@code ai.*} (see DECISIONS.md
 * ADR-024 §§10, 13).
 *
 * <p>All behavior stays inert unless {@code enabled} is true; binding
 * itself is lenient and the provider wiring validates only when the
 * feature is switched on, so stock deployments need no AI settings.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(

		boolean enabled,
		String provider,
		String baseUrl,
		String model,
		Duration connectTimeout,
		Duration requestTimeout,
		Integer maxInputChars,
		Integer maxOutputTokens

) {
}
