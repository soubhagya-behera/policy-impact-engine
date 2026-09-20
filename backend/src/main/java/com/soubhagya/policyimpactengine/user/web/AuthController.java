package com.soubhagya.policyimpactengine.user.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.RegistrationResult;
import com.soubhagya.policyimpactengine.user.web.dto.RegisterRequest;
import com.soubhagya.policyimpactengine.user.web.dto.RegistrationResponse;

import jakarta.validation.Valid;

/**
 * Phase 8A — account registration API. Thin: validates input,
 * delegates to the application service, and returns the safe DTO.
 * No login, no tokens, no authenticated endpoints in this slice.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthRegistrationService service;

	public AuthController(AuthRegistrationService service) {
		this.service = service;
	}

	@PostMapping("/register")
	public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
		RegistrationResult result = service.register(request.email(), request.password());
		URI location = URI.create("/api/v1/auth/users/" + result.id());
		return ResponseEntity.created(location).body(RegistrationResponse.from(result));
	}
}
