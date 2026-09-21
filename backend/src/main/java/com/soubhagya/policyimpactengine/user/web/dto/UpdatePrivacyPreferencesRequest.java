package com.soubhagya.policyimpactengine.user.web.dto;

import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Bulk sensitivity update. Each entry is upserted; concepts absent
 * from the map are left untouched (merge semantics, never replace).
 * Concept codes must exist in the vocabulary; unknown codes are
 * rejected by the service with a 400 problem response.
 */
public record UpdatePrivacyPreferencesRequest(

		@NotNull
		Map<String, @NotNull @Min(0) @Max(5) Integer> preferences

) {

}
