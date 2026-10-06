package com.soubhagya.policyimpactengine.user.web;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.user.GoogleCompletionService;
import com.soubhagya.policyimpactengine.user.LoginResult;
import com.soubhagya.policyimpactengine.user.web.dto.GoogleCompleteRequest;
import com.soubhagya.policyimpactengine.user.web.dto.LoginResponse;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Phase 18-B — Google sign-in handoff endpoints (see DECISIONS.md
 * ADR-037).
 *
 * <p>Thin: the OAuth dance itself is handled by Spring's OAuth2 login
 * infrastructure. {@code GET /start} only redirects (top-level browser
 * navigation, never fetch/XHR) into the {@code /oauth2/authorization}
 * entry point; {@code POST /complete} exchanges the short-lived
 * single-use completion code minted by the OAuth success bridge for
 * the standard {@link LoginResponse} token pair. Tokens never appear
 * in URLs: the redirect carries only the opaque code.
 *
 * <p>{@code AUTH_GOOGLE_LOGIN_SUCCEEDED} is emitted post-commit
 * best-effort on success (actor = local user, empty metadata);
 * unknown, expired, consumed, or concurrent-loser codes fail with
 * the uniform 401 and stay silent. No raw code, token, or secret is
 * ever logged or audited here.
 */
@RestController
@RequestMapping("/api/v1/auth/google")
public class GoogleAuthController {

	static final String AUTHORIZATION_ENTRY_POINT = "/oauth2/authorization/google";

	private final GoogleCompletionService completionService;
	private final AuditService auditService;

	public GoogleAuthController(GoogleCompletionService completionService,
			AuditService auditService) {
		if (completionService == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.completionService = completionService;
		this.auditService = auditService;
	}

	/**
	 * Starts Google sign-in: redirects the top-level browser navigation
	 * into Spring's OAuth2 authorization entry point, which owns the
	 * state/CSRF protection. Anonymous by design.
	 */
	@GetMapping("/start")
	public void start(HttpServletResponse response) throws IOException {
		response.sendRedirect(AUTHORIZATION_ENTRY_POINT);
	}

	/**
	 * Completes Google sign-in: consumes the one-time code and returns
	 * the standard token pair. Anonymous by design — the code itself is
	 * the credential.
	 */
	@PostMapping("/complete")
	public ResponseEntity<LoginResponse> complete(
			@Valid @RequestBody GoogleCompleteRequest request) {
		LoginResult result = completionService.complete(request.code());
		auditService.append(result.userId(), AuditEventType.AUTH_GOOGLE_LOGIN_SUCCEEDED,
				"USER", result.userId(), AuditMetadata.empty(), null);
		return ResponseEntity.ok(LoginResponse.from(result));
	}
}
