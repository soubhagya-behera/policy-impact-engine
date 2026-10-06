package com.soubhagya.policyimpactengine.user.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;

/**
 * Phase 18-B — Google completion request (see DECISIONS.md ADR-037).
 * Carries the one-time completion code only; never a user id, email,
 * Google subject, token, or any other caller-supplied identity. Unknown
 * properties are ignored so extra identity fields can never override the
 * code owner's user id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleCompleteRequest(

		@NotBlank
		String code

) {
}
