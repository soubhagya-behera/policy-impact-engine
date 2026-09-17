package com.soubhagya.policyimpactengine.user;

import java.util.List;
import java.util.Map;

import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreference;

/**
 * Phase 2P — pure effective sensitivity resolution.
 * No Spring, DB, HTTP, clock, randomness.
 */
public interface EffectiveSensitivityResolver {

	/**
	 * Resolves effective sensitivity for every concept in deterministic code order.
	 *
	 * @param preferences explicit user preferences (may be empty)
	 * @param concepts full vocabulary
	 * @return ordered map code → effective sensitivity 0..5
	 */
	Map<String, Integer> resolveAll(List<UserPrivacyPreference> preferences, List<PrivacyConcept> concepts);

	/**
	 * Resolves for a single concept.
	 */
	int resolve(UserPrivacyPreference preferenceOrNull, PrivacyConcept concept);
}
