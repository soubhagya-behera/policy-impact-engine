package com.soubhagya.policyimpactengine.common.web.cors;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 13-D — CORS configuration bound from {@code app.cors}
 * (see DECISIONS.md ADR-027).
 *
 * <p>Deny by default: an empty {@code allowed-origins} (the default)
 * registers no CORS mappings, so cross-origin browser calls are
 * blocked. When origins are configured they must be exact
 * {@code http(s)://host[:port]} values — no wildcards, no paths.
 * Methods and headers are locked to the API's real surface;
 * credentials stay {@code false}.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(

		List<String> allowedOrigins,
		List<String> allowedMethods,
		List<String> allowedHeaders,
		Boolean allowCredentials,
		Duration maxAge

) {

	private static final List<String> DEFAULT_METHODS =
			List.of("GET", "POST", "PUT");
	private static final List<String> DEFAULT_HEADERS =
			List.of("Authorization", "Content-Type");

	public CorsProperties {
		allowedOrigins = allowedOrigins == null
				? List.of() : List.copyOf(allowedOrigins);
		allowedMethods = allowedMethods == null
				? DEFAULT_METHODS : List.copyOf(allowedMethods);
		allowedHeaders = allowedHeaders == null
				? DEFAULT_HEADERS : List.copyOf(allowedHeaders);
		allowCredentials = allowCredentials != null && allowCredentials;
		maxAge = maxAge == null ? Duration.ofHours(1) : maxAge;

		for (String origin : allowedOrigins) {
			if (!isExactOrigin(origin)) {
				throw new IllegalArgumentException(
						"app.cors.allowed-origins must be exact http(s)://host[:port] origins");
			}
		}
		for (String method : allowedMethods) {
			if (method == null || !DEFAULT_METHODS.contains(method)) {
				throw new IllegalArgumentException(
						"app.cors.allowed-methods must be a subset of GET, POST, PUT");
			}
		}
		for (String header : allowedHeaders) {
			if (header == null || DEFAULT_HEADERS.stream()
					.noneMatch(allowed -> allowed.equalsIgnoreCase(header))) {
				throw new IllegalArgumentException(
						"app.cors.allowed-headers must be a subset of Authorization, Content-Type");
			}
		}
		if (allowCredentials) {
			throw new IllegalArgumentException(
					"app.cors.allow-credentials must be false in Phase 13-D");
		}
		if (maxAge.isZero() || maxAge.isNegative()) {
			throw new IllegalArgumentException("app.cors.max-age must be positive");
		}
	}

	private static boolean isExactOrigin(String origin) {
		if (origin == null || origin.isBlank()) {
			return false;
		}
		String lower = origin.toLowerCase();
		String rest;
		if (lower.startsWith("http://")) {
			rest = origin.substring("http://".length());
		}
		else if (lower.startsWith("https://")) {
			rest = origin.substring("https://".length());
		}
		else {
			return false;
		}
		return !rest.isBlank()
				&& !rest.contains("/")
				&& !rest.contains(" ")
				&& !rest.contains("*");
	}
}
