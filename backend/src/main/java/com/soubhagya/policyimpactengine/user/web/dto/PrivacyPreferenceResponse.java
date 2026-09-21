package com.soubhagya.policyimpactengine.user.web.dto;

/**
 * One privacy-concept sensitivity in the authenticated user's
 * effective preference surface.
 *
 * @param conceptCode stable concept identifier from the vocabulary
 * @param label human-readable concept label
 * @param effectiveSensitivity the sensitivity driving personalized
 * impact: the explicit value when configured, otherwise the
 * concept default
 * @param explicit true when the user configured this concept
 * explicitly; false when the value falls back to the default
 */
public record PrivacyPreferenceResponse(

		String conceptCode,
		String label,
		int effectiveSensitivity,
		boolean explicit

) {

}
