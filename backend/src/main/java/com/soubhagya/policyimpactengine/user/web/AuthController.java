package com.soubhagya.policyimpactengine.user.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.user.AuthLoginService;
import com.soubhagya.policyimpactengine.user.AuthRefreshService;
import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.user.LoginResult;
import com.soubhagya.policyimpactengine.user.RefreshResult;
import com.soubhagya.policyimpactengine.user.RegistrationResult;
import com.soubhagya.policyimpactengine.user.web.dto.LoginRequest;
import com.soubhagya.policyimpactengine.user.web.dto.LoginResponse;
import com.soubhagya.policyimpactengine.user.web.dto.RefreshRequest;
import com.soubhagya.policyimpactengine.user.web.dto.RefreshResponse;
import com.soubhagya.policyimpactengine.user.web.dto.RegisterRequest;
import com.soubhagya.policyimpactengine.user.web.dto.RegistrationResponse;

import jakarta.validation.Valid;

/**
 * Phase 8A — account registration API. Thin: validates input,
 * delegates to the application service, and returns the safe DTO.
 *
 * <p>Phase 8B adds login, which issues the short-lived access token.
 * No refresh tokens and no authenticated endpoints in this slice.
 *
 * <p>Phase 14-A/3b adds refresh rotation: {@code POST
 * /api/v1/auth/refresh} rotates the opaque refresh token and issues a
 * fresh access token for the rotation owner's user id (see DECISIONS.md
 * ADR-029). No audit event, transaction, or raw-token logging here.
 *
 * <p>Phase 11C emits audit events post-commit: {@code
 * AUTH_USER_REGISTERED} after a user is created, {@code
 * AUTH_LOGIN_SUCCEEDED} after credentials verify. Failed, duplicate,
 * or invalid attempts never reach the emit line, so they stay
 * silent. An audit failure propagates without undoing the committed
 * business state (see DECISIONS.md ADR-022). No credential, token,
 * or secret ever enters audit metadata.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthRegistrationService registrationService;
	private final AuthLoginService loginService;
	private final AuthRefreshService refreshService;
	private final JwtService jwtService;
	private final AuditService auditService;

	public AuthController(AuthRegistrationService registrationService, AuthLoginService loginService,
			AuthRefreshService refreshService, JwtService jwtService, AuditService auditService) {
		if (registrationService == null || loginService == null || refreshService == null
				|| jwtService == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.registrationService = registrationService;
		this.loginService = loginService;
		this.refreshService = refreshService;
		this.jwtService = jwtService;
		this.auditService = auditService;
	}

	@PostMapping("/register")
	public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
		RegistrationResult result = registrationService.register(request.email(), request.password());
		auditService.append(result.id(), AuditEventType.AUTH_USER_REGISTERED, "USER",
				result.id(), AuditMetadata.empty(), null);
		URI location = URI.create("/api/v1/auth/users/" + result.id());
		return ResponseEntity.created(location).body(RegistrationResponse.from(result));
	}

	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		LoginResult result = loginService.login(request.email(), request.password());
		auditService.append(result.userId(), AuditEventType.AUTH_LOGIN_SUCCEEDED, "USER",
				result.userId(), AuditMetadata.empty(), null);
		return ResponseEntity.ok(LoginResponse.from(result));
	}

	/**
	 * Phase 14-A/3b — opaque refresh-token rotation (see DECISIONS.md
	 * ADR-029). Validates and rotates the presented refresh token in its
	 * own short transaction via {@link AuthRefreshService}, then issues a
	 * fresh access token for the rotation owner's user id. Identity comes
	 * only from the rotation result — never from any caller-supplied
	 * field. No transaction, audit event, or logging of the raw token
	 * here.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
		RefreshResult rotated = refreshService.rotate(request.refreshToken());
		String accessToken = jwtService.issueAccessToken(rotated.userId());
		return ResponseEntity.ok(RefreshResponse.from(accessToken,
				jwtService.accessTokenExpiresInSeconds(), rotated));
	}
}
