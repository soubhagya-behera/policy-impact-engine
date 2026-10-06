package com.soubhagya.policyimpactengine.user.web;

import java.util.Base64;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Phase 18-B — cookie authorization-request store (see DECISIONS.md
 * ADR-037).
 *
 * <p>Spring's default {@code HttpSessionOAuth2AuthorizationRequestRepository}
 * would create an HTTP session, contradicting our
 * {@code SessionCreationPolicy.STATELESS} posture. This store keeps the
 * OAuth2 authorization request (state, PKCE verifier, redirect URI) in a
 * short-lived, {@code HttpOnly + Secure (on HTTPS) + SameSite=Lax}
 * cookie. The path is {@code /} because the two OAuth endpoints live
 * under different prefixes ({@code /oauth2/authorization/*} starts the
 * flow, {@code /login/oauth2/code/*} receives the provider callback)
 * and the browser must send the cookie to both. No application login
 * session is created, API Bearer authentication is untouched, and
 * normal CSRF behavior is unchanged.
 */
@Component
public class CookieAuthorizationRequestRepository
		implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	static final String COOKIE_NAME = "pie.oauth-request";
	static final int COOKIE_MAX_AGE_SECONDS = 300;

	private final tools.jackson.databind.ObjectMapper objectMapper;

	public CookieAuthorizationRequestRepository(
			tools.jackson.databind.ObjectMapper objectMapper) {
		if (objectMapper == null) {
			throw new IllegalArgumentException("ObjectMapper must not be null");
		}
		this.objectMapper = objectMapper;
	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		return readCookie(request);
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
			HttpServletRequest request, HttpServletResponse response) {
		if (authorizationRequest == null) {
			removeAuthorizationRequest(request, response);
			return;
		}
		try {
			String json = objectMapper.writeValueAsString(new Stored(authorizationRequest));
			String encoded = Base64.getUrlEncoder().withoutPadding()
					.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			Cookie cookie = new Cookie(COOKIE_NAME, encoded);
			cookie.setPath("/");
			cookie.setHttpOnly(true);
			cookie.setSecure(request.isSecure());
			cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
			response.addCookie(cookie);
			appendSameSiteLax(response);
		}
		catch (Exception failed) {
			throw new IllegalStateException("Failed to store OAuth2 request", failed);
		}
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		OAuth2AuthorizationRequest loaded = readCookie(request);
		Cookie cookie = new Cookie(COOKIE_NAME, "");
		cookie.setPath("/");
		cookie.setHttpOnly(true);
		cookie.setSecure(request.isSecure());
		cookie.setMaxAge(0);
		response.addCookie(cookie);
		return loaded;
	}

	/**
	 * Appends {@code SameSite=Lax} to the cookie just written. Lax keeps
	 * the top-level Google redirect a cookie-sending navigation while
	 * still withholding the cookie from third-party subrequests. The
	 * Servlet {@code Cookie} API carries no SameSite attribute, hence
	 * the header edit; a missing header (no cookie written) is a no-op.
	 */
	private static void appendSameSiteLax(HttpServletResponse response) {
		String setCookie = response.getHeader("Set-Cookie");
		if (setCookie != null && !setCookie.contains("SameSite=")) {
			response.setHeader("Set-Cookie", setCookie + "; SameSite=Lax");
		}
	}

	private OAuth2AuthorizationRequest readCookie(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.getCookies()) {
			if (COOKIE_NAME.equals(cookie.getName())) {
				try {
					byte[] decoded = Base64.getUrlDecoder().decode(cookie.getValue());
					String json = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
					return objectMapper.readValue(json, Stored.class).toRequest();
				}
				catch (RuntimeException invalid) {
					return null;
				}
				catch (Exception invalid) {
					return null;
				}
			}
		}
		return null;
	}

	/** Minimal serializable snapshot of the authorization request. */
	private record Stored(
			String authorizationUri,
			String clientId,
			String redirectUri,
			java.util.Set<String> scopes,
			String state,
			java.util.Map<String, Object> additionalParameters,
			String authorizationGrantType,
			java.util.Map<String, Object> attributes) {

		Stored(OAuth2AuthorizationRequest request) {
			this(request.getAuthorizationUri(), request.getClientId(),
					request.getRedirectUri(), request.getScopes(), request.getState(),
					request.getAdditionalParameters(),
					request.getGrantType() == null ? null : request.getGrantType().getValue(),
					request.getAttributes());
		}

		OAuth2AuthorizationRequest toRequest() {
			return OAuth2AuthorizationRequest.authorizationCode()
					.authorizationUri(authorizationUri).clientId(clientId)
					.redirectUri(redirectUri).scopes(scopes).state(state)
					.additionalParameters(additionalParameters == null
							? java.util.Map.of() : additionalParameters)
					.attributes(attributes == null ? java.util.Map.of() : attributes)
					.build();
		}
	}
}
