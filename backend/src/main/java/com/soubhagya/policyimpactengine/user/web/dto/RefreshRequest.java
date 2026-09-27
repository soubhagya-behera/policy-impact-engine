package com.soubhagya.policyimpactengine.user.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;

/**
 * Phase 14-A/3b — refresh request. Carries the opaque refresh token only;
 * never a user id, email, username, or any other caller-supplied identity.
 * Unknown properties are ignored so extra identity fields can never
 * override the refresh-token owner's user id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefreshRequest(

		@NotBlank
		String refreshToken

) {
}
