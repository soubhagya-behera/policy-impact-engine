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

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
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
 *
 * <p>Phase 11C emits {@code PRIVACY_PREFERENCE_UPSERTED} once per
 * actually created or changed preference and {@code
 * PRIVACY_PREFERENCE_DELETED} once per actually deleted row, each
 * after its entry commits — a best-effort witness that never rolls
 * back committed preferences (see DECISIONS.md ADR-022). Unchanged
 * rewrites, absent keys, and empty updates stay silent.
 */
@Service
public class UserPrivacyPreferenceService {

	private final UserRepository userRepository;
	private final PrivacyConceptRepository conceptRepository;
	private final UserPrivacyPreferenceRepository preferenceRepository;
	private final EffectiveSensitivityResolver resolver;
	private final TransactionTemplate writeTransaction;
	private final AuditService auditService;

	public UserPrivacyPreferenceService(UserRepository userRepository,
			PrivacyConceptRepository conceptRepository,
			UserPrivacyPreferenceRepository preferenceRepository,
			EffectiveSensitivityResolver resolver,
			PlatformTransactionManager transactionManager, AuditService auditService) {
		if (userRepository == null || conceptRepository == null || preferenceRepository == null || resolver == null
				|| transactionManager == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.userRepository = userRepository;
		this.conceptRepository = conceptRepository;
		this.preferenceRepository = preferenceRepository;
		this.resolver = resolver;
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.writeTransaction = template;
		this.auditService = auditService;
	}

	/**
	 * Outcome of one committed preference write: the previous
	 * explicit sensitivity ({@code null} when the row was created)
	 * and the new value. Unchanged rewrites carry equal values.
	 */
	private record EntryOutcome(Integer oldSensitivity, int newSensitivity) {
		boolean changed() {
			return oldSensitivity == null || oldSensitivity != newSensitivity;
		}
	}

	public UserPrivacyPreference upsertPreference(UUID userId, String conceptCode, int sensitivity) {
		if (userId == null) throw new IllegalArgumentException("User id must not be null");
		if (conceptCode == null || conceptCode.isBlank()) throw new IllegalArgumentException("Concept code must not be blank");
		if (sensitivity < 0 || sensitivity > 5) throw new IllegalArgumentException("Sensitivity must be between 0 and 5");
		userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		PrivacyConcept concept = conceptRepository.findByCode(conceptCode.trim())
				.orElseThrow(() -> new IllegalArgumentException("Concept not found: " + conceptCode));
		EntryOutcome outcome = writeEntry(userId, concept, sensitivity);
		if (outcome.changed()) {
			emitPreferenceUpserted(userId, concept.getCode(), outcome.oldSensitivity(), sensitivity);
		}
		return preferenceRepository.findByUser_IdAndConcept_Id(userId, concept.getId())
				.orElseThrow(() -> new IllegalStateException("Preference missing after upsert"));
	}

	public boolean deletePreference(UUID userId, String conceptCode) {
		if (userId == null || conceptCode == null || conceptCode.isBlank()) throw new IllegalArgumentException("Ids must not be blank");
		String code = conceptCode.trim();
		Integer removed = writeTransaction.execute(status -> {
			UserPrivacyPreference existing = preferenceRepository
					.findByUser_IdAndConcept_Code(userId, code).orElse(null);
			if (existing == null) {
				return null;
			}
			int oldSensitivity = existing.getSensitivity();
			preferenceRepository.delete(existing);
			return oldSensitivity;
		});
		if (removed == null) {
			return false;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("conceptCode", code);
		fields.put("oldSensitivity", removed);
		auditService.append(userId, AuditEventType.PRIVACY_PREFERENCE_DELETED, "USER",
				userId, AuditMetadata.of(fields), null);
		return true;
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
			EntryOutcome outcome = writeEntry(userId, entry.getKey(), entry.getValue());
			if (outcome.changed()) {
				emitPreferenceUpserted(userId, entry.getKey().getCode(),
						outcome.oldSensitivity(), entry.getValue());
			}
		}
		return getPreferences(userId);
	}

	private void emitPreferenceUpserted(UUID userId, String conceptCode,
			Integer oldSensitivity, int newSensitivity) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("conceptCode", conceptCode);
		fields.put("oldSensitivity", oldSensitivity);
		fields.put("newSensitivity", newSensitivity);
		auditService.append(userId, AuditEventType.PRIVACY_PREFERENCE_UPSERTED, "USER",
				userId, AuditMetadata.of(fields), null);
	}

	private EntryOutcome writeEntry(UUID userId, PrivacyConcept concept, int sensitivity) {
		try {
			return writeTransaction.execute(
					status -> upsertEntry(userId, concept, sensitivity));
		}
		catch (DataIntegrityViolationException duplicate) {
			return writeTransaction.execute(status -> {
				UserPrivacyPreference existing = preferenceRepository
						.findByUser_IdAndConcept_Id(userId, concept.getId())
						.orElseThrow(() -> duplicate);
				int oldSensitivity = existing.getSensitivity();
				existing.updateSensitivity(sensitivity);
				preferenceRepository.saveAndFlush(existing);
				return new EntryOutcome(oldSensitivity, sensitivity);
			});
		}
	}

	private EntryOutcome upsertEntry(UUID userId, PrivacyConcept concept, int sensitivity) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		UserPrivacyPreference existing = preferenceRepository
				.findByUser_IdAndConcept_Id(userId, concept.getId()).orElse(null);
		if (existing == null) {
			preferenceRepository.saveAndFlush(new UserPrivacyPreference(user, concept, sensitivity));
			return new EntryOutcome(null, sensitivity);
		}
		int oldSensitivity = existing.getSensitivity();
		existing.updateSensitivity(sensitivity);
		preferenceRepository.saveAndFlush(existing);
		return new EntryOutcome(oldSensitivity, sensitivity);
	}
}
