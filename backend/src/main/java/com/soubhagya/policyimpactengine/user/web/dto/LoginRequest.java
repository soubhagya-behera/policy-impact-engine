package com.soubhagya.policyimpactengine.user.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Phase 8B — login request.
 *
 * <p>Password carries no length validation here on purpose: every
 * credential problem (including out-of-policy lengths) must surface
 * as the uniform login 401, never as a distinguishable 400.
 */
public record LoginRequest(

		@NotBlank
		@Email
		@Size(max = 254)
		String email,

		@NotBlank
		String password

) {

}
