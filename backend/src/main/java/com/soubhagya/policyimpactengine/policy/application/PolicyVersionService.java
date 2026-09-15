package com.soubhagya.policyimpactengine.policy.application;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;

/**
 * Observes already-processed policy content and persists immutable versions
 * with exact SHA-256 change detection.
 *
 * <p>Scope is deliberately narrow: the caller supplies the canonical
 * normalized content and its SHA-256 hash (produced upstream by extraction,
 * normalization, and hashing). This service never fetches URLs, extracts
 * HTML, normalizes text, or computes hashes; it owns only version
 * persistence and change detection.
 *
 * <p>Behavior for {@code observe(policyId, normalizedContent, contentHash)}:
 * <ul>
 * <li>No existing version → create version 1, report {@code FIRST_VERSION}.</li>
 * <li>Latest version has the same hash → create nothing, report {@code UNCHANGED}.</li>
 * <li>Latest version has a different hash → create the next version number,
 * report {@code NEW_VERSION}.</li>
 * </ul>
 *
 * <p><b>Concurrency:</b> correctness rests on the database unique constraint
 * {@code uq_policy_version_policy_number} over {@code (policy_id,
 * version_number)}, not on in-memory synchronization. Each observation runs
 * in its own transaction: it reads the latest version, compares hashes, and
 * inserts the next number. Two concurrent observations of the same policy
 * may both read the same latest version and attempt the same next number;
 * the database admits exactly one insert and rejects the other with a
 * {@link DataIntegrityViolationException}, so duplicate version numbers are
 * impossible. The losing call rolls back cleanly with no partial state, and
 * simply retrying the (idempotent) {@code observe} call converges: identical
 * content then reports {@code UNCHANGED}, changed content takes the
 * following version number.
 *
 * <p><b>Remaining limitation (intentional, future slice):</b> the service
 * itself performs no automatic retry — a concurrent loser surfaces
 * {@link DataIntegrityViolationException} to the caller. Retrying is the
 * responsibility of the future observation-orchestration slice (manual
 * "check now" / scheduler), which owns retry policy. No distributed locking
 * is introduced here.
 */
@Service
public class PolicyVersionService {

	private final PolicyRepository policyRepository;
	private final PolicyVersionRepository versionRepository;

	public PolicyVersionService(PolicyRepository policyRepository, PolicyVersionRepository versionRepository) {
		this.policyRepository = policyRepository;
		this.versionRepository = versionRepository;
	}

	/**
	 * Observes content for the given policy using exact hash comparison.
	 *
	 * @param policyId identifier of an already-registered policy
	 * @param normalizedContent canonical normalized content; must not be null
	 * @param contentHash SHA-256 hash of the normalized content; must not be blank
	 * @return the detection outcome with the created or current version
	 */
	@Transactional
	public PolicyVersionObservation observe(UUID policyId, String normalizedContent, String contentHash) {
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		if (normalizedContent == null) {
			throw new IllegalArgumentException("Normalized content must not be null");
		}
		if (contentHash == null || contentHash.isBlank()) {
			throw new IllegalArgumentException("Content hash must not be blank");
		}

		Policy policy = policyRepository.findById(policyId)
				.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));

		var latest = versionRepository.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId());

		if (latest.isPresent() && latest.get().getContentHash().equals(contentHash)) {
			return new PolicyVersionObservation(PolicyVersionObservationOutcome.UNCHANGED, latest.get());
		}

		int nextNumber = latest.map(v -> v.getVersionNumber() + 1).orElse(1);
		boolean firstVersion = latest.isEmpty();

		PolicyVersion saved = versionRepository
				.saveAndFlush(new PolicyVersion(policy, nextNumber, normalizedContent, contentHash));
		return new PolicyVersionObservation(
				firstVersion ? PolicyVersionObservationOutcome.FIRST_VERSION
						: PolicyVersionObservationOutcome.NEW_VERSION,
				saved);
	}

}
