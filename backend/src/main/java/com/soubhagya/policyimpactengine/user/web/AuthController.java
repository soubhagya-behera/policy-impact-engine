package com.soubhagya.policyimpactengine.user.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthRegistrationService registrationService;
	private final AuthLoginService loginService;

	public AuthController(AuthRegistrationService registrationService, AuthLoginService loginService) {
		this.registrationService = registrationService;
		this.loginService = loginService;
	}

	@PostMapping("/register")
	public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
		RegistrationResult result = registrationService.register(request.email(), request.password());
		URI location = URI.create("/api/v1/auth/users/" + result.id());
		return ResponseEntity.created(location).body(RegistrationResponse.from(result));
	}

	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		LoginResult result = loginService.login(request.email(), request.password());
		return ResponseEntity.ok(LoginResponse.from(result));
	}
}
