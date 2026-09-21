package com.soubhagya.policyimpactengine.user.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.user.UserPrivacyPreferenceService;
import com.soubhagya.policyimpactengine.user.web.dto.PrivacyPreferenceResponse;
import com.soubhagya.policyimpactengine.user.web.dto.UpdatePrivacyPreferencesRequest;

import jakarta.validation.Valid;

/**
 * Authenticated privacy-preference API. Thin: resolves the user id
 * exclusively from the authenticated principal, delegates to the
 * application service, and returns DTOs.
 *
 * <p>Identity never comes from the request body, query parameters,
 * headers, or path variables. The preference surface always covers
 * every vocabulary concept in deterministic code order, marking
 * explicitly configured values.
 */
@RestController
@RequestMapping("/api/v1/me/privacy-preferences")
public class PrivacyPreferenceController {

	private final UserPrivacyPreferenceService service;

	public PrivacyPreferenceController(UserPrivacyPreferenceService service) {
		if (service == null) {
			throw new IllegalArgumentException("UserPrivacyPreferenceService must not be null");
		}
		this.service = service;
	}

	@GetMapping
	public List<PrivacyPreferenceResponse> get(Authentication authentication) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.getPreferences(userId);
	}

	@PutMapping
	public List<PrivacyPreferenceResponse> update(Authentication authentication,
			@Valid @RequestBody UpdatePrivacyPreferencesRequest request) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.bulkUpdatePreferences(userId, request.preferences());
	}

}
