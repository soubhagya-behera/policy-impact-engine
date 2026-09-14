package com.soubhagya.policyimpactengine.policy.web;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Registration-time validation for user-supplied policy URLs.
 *
 * <p>Only syntactic and scheme rules are enforced here. Network-level SSRF
 * protection (DNS resolution, IP-range blocking, redirect revalidation)
 * belongs to the fetch phase and must run before any external request.
 */
public final class PolicyUrlValidator {

	public static final int MAX_URL_LENGTH = 2048;

	private PolicyUrlValidator() {
	}

	/**
	 * Validates the given URL and returns its trimmed form.
	 *
	 * @throws IllegalArgumentException if the URL violates the registration contract
	 */
	public static String validate(String url) {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("Policy URL must not be blank");
		}
		String trimmed = url.trim();
		if (trimmed.length() > MAX_URL_LENGTH) {
			throw new IllegalArgumentException("Policy URL must not exceed 2048 characters");
		}
		URI uri;
		try {
			uri = new URI(trimmed);
		}
		catch (URISyntaxException | IllegalArgumentException ex) {
			throw new IllegalArgumentException("Policy URL is not a valid URL");
		}
		if (uri.getScheme() == null) {
			throw new IllegalArgumentException("Policy URL must be an absolute URL with a host");
		}
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalArgumentException("Policy URL must use https");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IllegalArgumentException("Policy URL must contain a valid host");
		}
		if (uri.getUserInfo() != null) {
			throw new IllegalArgumentException("Policy URL must not contain credentials");
		}
		return trimmed;
	}

}
