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
import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.LoginResult;
import com.soubhagya.policyimpactengine.user.RegistrationResult;
import com.soubhagya.policyimpactengine.user.web.dto.LoginRequest;
import com.soubhagya.policyimpactengine.user.web.dto.LoginResponse;
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
	private final AuditService auditService;

	public AuthController(AuthRegistrationService registrationService, AuthLoginService loginService,
			AuditService auditService) {
		if (registrationService == null || loginService == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.registrationService = registrationService;
		this.loginService = loginService;
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
}
