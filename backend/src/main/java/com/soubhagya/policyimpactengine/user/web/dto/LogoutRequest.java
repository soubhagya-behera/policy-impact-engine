package com.soubhagya.policyimpactengine.user.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;

/**
 * Phase 15-A/2 — logout request (see DECISIONS.md ADR-031). Carries the
 * opaque refresh token only; never a user id, email, username, or any
 * other caller-supplied identity. Unknown properties are ignored so
 * extra identity fields can never select another user's session.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogoutRequest(

		@NotBlank
		String refreshToken

) {
}
