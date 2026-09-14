package com.soubhagya.policyimpactengine.policy.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.policy.domain.Policy;

/**
 * Representation of a registered policy returned by the API.
 */
public record PolicyResponse(

		UUID id,
		String name,
		String url,
		String status,
		Instant createdAt,
		Instant updatedAt

) {

	public static PolicyResponse from(Policy policy) {
		return new PolicyResponse(
				policy.getId(),
				policy.getName(),
				policy.getUrl(),
				policy.getStatus().name(),
				policy.getCreatedAt(),
				policy.getUpdatedAt());
	}

}
