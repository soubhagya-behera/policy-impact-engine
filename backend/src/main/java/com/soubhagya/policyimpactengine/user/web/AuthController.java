package com.soubhagya.policyimpactengine.user.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.user.AuthLoginService;
import com.soubhagya.policyimpactengine.user.AuthLogoutService;
import com.soubhagya.policyimpactengine.user.AuthRefreshService;
import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.user.LoginResult;
import com.soubhagya.policyimpactengine.user.LogoutResult;
import com.soubhagya.policyimpactengine.user.RefreshResult;
import com.soubhagya.policyimpactengine.user.RefreshReuseDetectedException;
import com.soubhagya.policyimpactengine.user.RegistrationResult;
import com.soubhagya.policyimpactengine.user.web.dto.LoginRequest;
import com.soubhagya.policyimpactengine.user.web.dto.LoginResponse;
import com.soubhagya.policyimpactengine.user.web.dto.LogoutRequest;
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
 *
 * <p>Phase 15-A/2 adds logout (see DECISIONS.md ADR-031):
 * anonymous {@code POST /api/v1/auth/logout} revokes the presented
 * refresh session (idempotent {@code 204 No Content} on every token
 * state, never 401/403/404 for token state) and authenticated
 * {@code POST /api/v1/auth/logout-all} revokes every live session of
 * the principal (idempotent {@code 204}). {@code AUTH_LOGOUT_SUCCEEDED}
 * / {@code AUTH_LOGOUT_ALL_SUCCEEDED} are emitted post-commit only on
 * actual revocation; no-ops stay silent. No raw token, digest, or
 * secret is ever logged or audited.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthRegistrationService registrationService;
	private final AuthLoginService loginService;
	private final AuthRefreshService refreshService;
	private final AuthLogoutService logoutService;
	private final JwtService jwtService;
	private final AuditService auditService;

	public AuthController(AuthRegistrationService registrationService, AuthLoginService loginService,
			AuthRefreshService refreshService, AuthLogoutService logoutService,
			JwtService jwtService, AuditService auditService) {
		if (registrationService == null || loginService == null || refreshService == null
				|| logoutService == null || jwtService == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.registrationService = registrationService;
		this.loginService = loginService;
		this.refreshService = refreshService;
		this.logoutService = logoutService;
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
	 *
	 * <p>Phase 15-B/2 — reuse witness (see DECISIONS.md ADR-032): a
	 * {@link RefreshReuseDetectedException} carries a committed family
	 * kill, so the {@code AUTH_REFRESH_REUSE_DETECTED} audit is appended
	 * post-commit best-effort (actor/resource from the exception, empty
	 * metadata) and the caller still receives the byte-identical uniform
	 * 401 — including when the audit append itself fails, which never
	 * rolls back the committed revocation.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
		try {
			RefreshResult rotated = refreshService.rotate(request.refreshToken());
			String accessToken = jwtService.issueAccessToken(rotated.userId());
			return ResponseEntity.ok(RefreshResponse.from(accessToken,
					jwtService.accessTokenExpiresInSeconds(), rotated));
		}
		catch (RefreshReuseDetectedException reuse) {
			if (reuse.getRevokedLiveCount() > 0) {
				try {
					auditService.append(reuse.getUserId(),
							AuditEventType.AUTH_REFRESH_REUSE_DETECTED, "USER",
							reuse.getUserId(), AuditMetadata.empty(), null);
				}
				catch (RuntimeException auditFailure) {
					throw reuse;
				}
			}
			throw reuse;
		}
	}

	/**
	 * Phase 15-A/2 — anonymous single-session logout (see DECISIONS.md
	 * ADR-031). Revokes the presented refresh session when it is still
	 * live; unknown, expired, revoked, and superseded tokens all yield
	 * the same idempotent {@code 204 No Content} with no family
	 * revocation and no audit event. Identity for the audit witness
	 * comes only from the revoked row — never from request data. No
	 * transaction, token return, or raw-token logging here.
	 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
		LogoutResult result = logoutService.logoutSingle(request.refreshToken());
		if (result.revoked()) {
			auditService.append(result.userId(), AuditEventType.AUTH_LOGOUT_SUCCEEDED, "USER",
					result.userId(), AuditMetadata.empty(), null);
		}
		return ResponseEntity.noContent().build();
	}

	/**
	 * Phase 15-A/2 — authenticated all-sessions logout (see DECISIONS.md
	 * ADR-031). Revokes every live refresh session of the principal,
	 * resolved exclusively from the authenticated principal; zero live
	 * rows is a successful no-op. Missing or invalid tokens keep the
	 * existing 401 behavior. No request body, no client-supplied
	 * identity, no raw-token logging here.
	 */
	@PostMapping("/logout-all")
	public ResponseEntity<Void> logoutAll(Authentication authentication) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		LogoutResult result = logoutService.logoutAll(userId);
		if (result.revoked()) {
			auditService.append(userId, AuditEventType.AUTH_LOGOUT_ALL_SUCCEEDED, "USER",
					userId, AuditMetadata.empty(), null);
		}
		return ResponseEntity.noContent().build();
	}
}
