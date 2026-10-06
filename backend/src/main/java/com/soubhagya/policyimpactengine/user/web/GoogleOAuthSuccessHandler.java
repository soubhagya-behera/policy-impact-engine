package com.soubhagya.policyimpactengine.user.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.soubhagya.policyimpactengine.user.AuthGoogleService;
import com.soubhagya.policyimpactengine.user.GoogleCompletionService;
import com.soubhagya.policyimpactengine.user.GoogleIdentity;
import com.soubhagya.policyimpactengine.user.InvalidGoogleIdentityException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Phase 18-B — Google OAuth2 success bridge (see DECISIONS.md ADR-037).
 *
 * <p>Handles Spring's validated OIDC principal only: Spring already
 * verified the signature, issuer, audience, expiry, and state before this
 * handler runs. Resolves the local user through {@link AuthGoogleService}
 * (verified email required, no email-only merge), mints a one-time
 * completion code, and redirects to the SPA callback path carrying only
 * that code. Our access and refresh tokens never appear in URLs.
 * Failures redirect to the login page with a generic error flag; no
 * provider, token, or code detail is ever logged or exposed.
 */
@Component
public class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {

	static final String CALLBACK_PATH = "/auth/google/callback";
	static final String FAILURE_PATH = "/login?error=google";

	private final AuthGoogleService googleService;
	private final GoogleCompletionService completionService;
	private final GoogleOAuthProperties properties;

	public GoogleOAuthSuccessHandler(AuthGoogleService googleService,
			GoogleCompletionService completionService, GoogleOAuthProperties properties) {
		if (googleService == null || completionService == null || properties == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.googleService = googleService;
		this.completionService = completionService;
		this.properties = properties;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.core.Authentication authentication) throws java.io.IOException {
		String code = null;
		try {
			GoogleIdentity identity = toIdentity(authentication);
			code = completionService.issueCode(googleService.resolveLocalUser(identity));
		}
		catch (RuntimeException rejected) {
			redirect(response, FAILURE_PATH);
			return;
		}
		redirect(response, CALLBACK_PATH + "?code=" + urlEncode(code));
	}

	private static GoogleIdentity toIdentity(
			org.springframework.security.core.Authentication authentication) {
		if (!(authentication instanceof OAuth2AuthenticationToken token)
				|| !(token.getPrincipal() instanceof OidcUser oidc)) {
			throw new InvalidGoogleIdentityException("Invalid Google identity");
		}
		Map<String, Object> claims = oidc.getClaims();
		Object verified = claims.get("email_verified");
		boolean emailVerified = verified instanceof Boolean b ? b : false;
		String email = oidc.getEmail();
		String subject = oidc.getSubject();
		if (subject == null || subject.isBlank() || email == null || email.isBlank()
				|| !emailVerified) {
			throw new InvalidGoogleIdentityException("Invalid Google identity");
		}
		return new GoogleIdentity(subject, email, true);
	}

	private void redirect(HttpServletResponse response, String path) throws java.io.IOException {
		response.sendRedirect(properties.frontendCallbackBase() + path);
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
