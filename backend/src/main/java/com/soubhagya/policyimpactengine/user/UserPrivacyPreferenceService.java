package com.soubhagya.policyimpactengine.user;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreference;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;
import com.soubhagya.policyimpactengine.user.web.dto.PrivacyPreferenceResponse;

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
	private final TransactionTemplate writeTransaction;

	public UserPrivacyPreferenceService(UserRepository userRepository,
			PrivacyConceptRepository conceptRepository,
			UserPrivacyPreferenceRepository preferenceRepository,
			EffectiveSensitivityResolver resolver,
			PlatformTransactionManager transactionManager) {
		if (userRepository == null || conceptRepository == null || preferenceRepository == null || resolver == null
				|| transactionManager == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.userRepository = userRepository;
		this.conceptRepository = conceptRepository;
		this.preferenceRepository = preferenceRepository;
		this.resolver = resolver;
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.writeTransaction = template;
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

	/**
	 * REST-facing effective preference surface: every vocabulary
	 * concept in deterministic code order, each carrying its effective
	 * sensitivity (explicit value or concept default) and whether the
	 * user configured it explicitly. Resolution reuses the existing
	 * {@link EffectiveSensitivityResolver}; no second algorithm.
	 */
	@Transactional(readOnly = true)
	public List<PrivacyPreferenceResponse> getPreferences(UUID userId) {
		if (userId == null) throw new IllegalArgumentException("User id must not be null");
		userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		List<UserPrivacyPreference> prefs = preferenceRepository.findByUser_IdOrderByConcept_CodeAsc(userId);
		List<PrivacyConcept> concepts = conceptRepository.findAll();
		Map<String, Integer> effective = resolver.resolveAll(prefs, concepts);
		Map<String, PrivacyConcept> conceptByCode = new LinkedHashMap<>();
		for (PrivacyConcept concept : concepts) {
			conceptByCode.put(concept.getCode(), concept);
		}
		Map<String, Integer> explicitByCode = new LinkedHashMap<>();
		for (UserPrivacyPreference preference : prefs) {
			explicitByCode.put(preference.getConcept().getCode(), preference.getSensitivity());
		}
		List<PrivacyPreferenceResponse> responses = new ArrayList<>(effective.size());
		for (Map.Entry<String, Integer> entry : effective.entrySet()) {
			PrivacyConcept concept = conceptByCode.get(entry.getKey());
			responses.add(new PrivacyPreferenceResponse(entry.getKey(), concept.getLabel(),
					entry.getValue(), explicitByCode.containsKey(entry.getKey())));
		}
		return responses;
	}

	/**
	 * REST-facing bulk update with merge semantics: every entry is
	 * upserted (existing rows updated, never duplicated); concepts
	 * absent from the request are left untouched. All entries are
	 * validated before anything is written. Each entry persists in
	 * its own short transaction so a lost-insert race on the
	 * UNIQUE(user_id, concept_id) guard converges through re-read,
	 * with no Java synchronization.
	 *
	 * @return the updated effective preference surface
	 */
	public List<PrivacyPreferenceResponse> bulkUpdatePreferences(UUID userId, Map<String, Integer> updates) {
		if (userId == null) throw new IllegalArgumentException("User id must not be null");
		if (updates == null) throw new IllegalArgumentException("Preferences must not be null");
		userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		Map<PrivacyConcept, Integer> validated = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : updates.entrySet()) {
			String code = entry.getKey() == null ? null : entry.getKey().trim();
			if (code == null || code.isBlank()) throw new IllegalArgumentException("Concept code must not be blank");
			Integer sensitivity = entry.getValue();
			if (sensitivity == null || sensitivity < 0 || sensitivity > 5) {
				throw new IllegalArgumentException("Sensitivity must be between 0 and 5");
			}
			PrivacyConcept concept = conceptRepository.findByCode(code)
					.orElseThrow(() -> new IllegalArgumentException("Concept not found: " + code));
			validated.put(concept, sensitivity);
		}
		for (Map.Entry<PrivacyConcept, Integer> entry : validated.entrySet()) {
			writeEntry(userId, entry.getKey(), entry.getValue());
		}
		return getPreferences(userId);
	}

	private void writeEntry(UUID userId, PrivacyConcept concept, int sensitivity) {
		try {
			writeTransaction.execute(status -> {
				upsertEntry(userId, concept, sensitivity);
				return null;
			});
		}
		catch (DataIntegrityViolationException duplicate) {
			writeTransaction.execute(status -> {
				UserPrivacyPreference existing = preferenceRepository
						.findByUser_IdAndConcept_Id(userId, concept.getId())
						.orElseThrow(() -> duplicate);
				existing.updateSensitivity(sensitivity);
				preferenceRepository.saveAndFlush(existing);
				return null;
			});
		}
	}

	private void upsertEntry(UUID userId, PrivacyConcept concept, int sensitivity) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		preferenceRepository.findByUser_IdAndConcept_Id(userId, concept.getId())
				.map(existing -> {
					existing.updateSensitivity(sensitivity);
					return preferenceRepository.saveAndFlush(existing);
				})
				.orElseGet(() -> preferenceRepository
						.saveAndFlush(new UserPrivacyPreference(user, concept, sensitivity)));
	}
}
