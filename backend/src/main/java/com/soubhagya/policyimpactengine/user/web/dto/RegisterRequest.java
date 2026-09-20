package com.soubhagya.policyimpactengine.user.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Phase 8A — account registration request.
 *
 * <p>The 72 UTF-8-byte password ceiling is enforced by the service
 * (BCrypt truncates beyond 72 bytes, so longer input is rejected,
 * never silently truncated); the DTO char limit stays generous so the
 * byte guard remains authoritative.
 */
public record RegisterRequest(

		@NotBlank
		@Email
		@Size(max = 254)
		String email,

		@NotBlank
		@Size(min = 8, max = 256)
		String password

) {

}
