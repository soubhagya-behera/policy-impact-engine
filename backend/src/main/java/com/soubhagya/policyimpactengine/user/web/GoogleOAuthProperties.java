package com.soubhagya.policyimpactengine.user.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 18-B — Google OAuth frontend redirect base (see DECISIONS.md
 * ADR-037).
 *
 * <p>Bound from {@code app.google.frontend-base-url}: the exact origin the
 * backend redirects to after the Google callback (local default
 * {@code http://localhost:5173}). Production sets the exact Vercel origin.
 * Only exact {@code http(s)://host[:port]} values are accepted.
 */
@ConfigurationProperties(prefix = "app.google")
public record GoogleOAuthProperties(

		String frontendBaseUrl

) {

	public GoogleOAuthProperties {
		frontendBaseUrl = frontendBaseUrl == null || frontendBaseUrl.isBlank()
				? "http://localhost:5173"
				: frontendBaseUrl;
		if (!isExactOrigin(frontendBaseUrl)) {
			throw new IllegalArgumentException(
					"app.google.frontend-base-url must be an exact http(s)://host[:port] origin");
		}
	}

	String frontendCallbackBase() {
		return frontendBaseUrl.replaceAll("/+$", "");
	}

	private static boolean isExactOrigin(String origin) {
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
		return !rest.isBlank() && !rest.contains("/") && !rest.contains(" ")
				&& !rest.contains("*");
	}
}
