package com.soubhagya.policyimpactengine.user;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreference;

/**
 * Phase 2P — deterministic resolver, pure Java.
 * Missing row → PrivacyConcept.defaultSensitivity; explicit 0 overrides default.
 */
public class DeterministicEffectiveSensitivityResolver implements EffectiveSensitivityResolver {

	@Override
	public Map<String, Integer> resolveAll(List<UserPrivacyPreference> preferences, List<PrivacyConcept> concepts) {
		if (preferences == null) {
			throw new IllegalArgumentException("Preferences must not be null");
		}
		if (concepts == null) {
			throw new IllegalArgumentException("Concepts must not be null");
		}
		if (concepts.isEmpty()) {
			return Map.of();
		}
		Map<String, Integer> prefByCode = preferences.stream().collect(Collectors.toMap(
				p -> p.getConcept().getCode(), UserPrivacyPreference::getSensitivity, (a, b) -> b, LinkedHashMap::new));
		// Deterministic code order
		List<PrivacyConcept> ordered = new ArrayList<>(concepts);
		ordered.sort((a, b) -> a.getCode().compareTo(b.getCode()));
		Map<String, Integer> result = new LinkedHashMap<>();
		for (PrivacyConcept c : ordered) {
			Integer explicit = prefByCode.get(c.getCode());
			int effective = explicit != null ? explicit : c.getDefaultSensitivity();
			validate(effective);
			result.put(c.getCode(), effective);
		}
		return Collections.unmodifiableMap(result);
	}

	@Override
	public int resolve(UserPrivacyPreference preferenceOrNull, PrivacyConcept concept) {
		if (concept == null) {
			throw new IllegalArgumentException("Concept must not be null");
		}
		if (preferenceOrNull != null) {
			int s = preferenceOrNull.getSensitivity();
			validate(s);
			// Ensure preference matches concept code for safety (deterministic, no silent cross)
			if (!preferenceOrNull.getConcept().getCode().equals(concept.getCode())) {
				throw new IllegalArgumentException("Preference concept does not match given concept");
			}
			return s;
		}
		int fallback = concept.getDefaultSensitivity();
		validate(fallback);
		return fallback;
	}

	private void validate(int s) {
		if (s < 0 || s > 5) {
			throw new IllegalArgumentException("Sensitivity must be between 0 and 5");
		}
	}
}
