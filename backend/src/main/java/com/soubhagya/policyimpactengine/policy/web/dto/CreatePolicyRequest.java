package com.soubhagya.policyimpactengine.policy.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for registering a policy.
 */
public record CreatePolicyRequest(

		@NotBlank
		@Size(max = 255)
		String name,

		@NotBlank
		@Size(max = 2048)
		String url

) {

}
