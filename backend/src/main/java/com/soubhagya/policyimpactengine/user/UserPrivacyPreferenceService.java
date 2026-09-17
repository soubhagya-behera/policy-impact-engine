package com.soubhagya.policyimpactengine.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreference;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 2P — minimal preference upsert/delete + effective resolution.
 * No REST endpoints in this phase (auth-deferred). APIs explicitly take userId.
 */
@Service
public class UserPrivacyPreferenceService {

	private final UserRepository userRepository;
	private final PrivacyConceptRepository conceptRepository;
	private final UserPrivacyPreferenceRepository preferenceRepository;
	private final EffectiveSensitivityResolver resolver;

	public UserPrivacyPreferenceService(UserRepository userRepository,
			PrivacyConceptRepository conceptRepository,
			UserPrivacyPreferenceRepository preferenceRepository,
			EffectiveSensitivityResolver resolver) {
		if (userRepository == null || conceptRepository == null || preferenceRepository == null || resolver == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.userRepository = userRepository;
		this.conceptRepository = conceptRepository;
		this.preferenceRepository = preferenceRepository;
		this.resolver = resolver;
	}

	@Transactional
	public UserPrivacyPreference upsertPreference(UUID userId, String conceptCode, int sensitivity) {
		if (userId == null) throw new IllegalArgumentException("User id must not be null");
		if (conceptCode == null || conceptCode.isBlank()) throw new IllegalArgumentException("Concept code must not be blank");
		if (sensitivity < 0 || sensitivity > 5) throw new IllegalArgumentException("Sensitivity must be between 0 and 5");
		User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		PrivacyConcept concept = conceptRepository.findByCode(conceptCode.trim())
				.orElseThrow(() -> new IllegalArgumentException("Concept not found: " + conceptCode));
		return preferenceRepository.findByUser_IdAndConcept_Id(userId, concept.getId())
				.map(existing -> {
					existing.updateSensitivity(sensitivity);
					return preferenceRepository.saveAndFlush(existing);
				})
				.orElseGet(() -> preferenceRepository.saveAndFlush(new UserPrivacyPreference(user, concept, sensitivity)));
	}

	@Transactional
	public void deletePreference(UUID userId, String conceptCode) {
		if (userId == null || conceptCode == null || conceptCode.isBlank()) throw new IllegalArgumentException("Ids must not be blank");
		UserPrivacyPreference existing = preferenceRepository.findByUser_IdAndConcept_Code(userId, conceptCode.trim()).orElse(null);
		if (existing != null) {
			preferenceRepository.delete(existing);
		}
	}

	@Transactional(readOnly = true)
	public Map<String, Integer> effectiveSensitivities(UUID userId) {
		if (userId == null) throw new IllegalArgumentException("User id must not be null");
		userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		List<UserPrivacyPreference> prefs = preferenceRepository.findByUser_IdOrderByConcept_CodeAsc(userId);
		List<PrivacyConcept> concepts = conceptRepository.findAll();
		return resolver.resolveAll(prefs, concepts);
	}

	@Transactional(readOnly = true)
	public List<UserPrivacyPreference> listPreferences(UUID userId) {
		if (userId == null) throw new IllegalArgumentException("User id must not be null");
		return preferenceRepository.findByUser_IdOrderByConcept_CodeAsc(userId);
	}
}
