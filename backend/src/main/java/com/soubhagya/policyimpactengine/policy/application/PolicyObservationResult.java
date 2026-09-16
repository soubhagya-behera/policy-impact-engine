package com.soubhagya.policyimpactengine.policy.application;

import java.util.Optional;
import java.util.UUID;

import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.SimHashSimilarity;

/**
 * Application-level result of a policy observation.
 *
 * <p>Carries only scalar data and the application-level diff and similarity
 * values — never JPA entities — so callers (and any future web layer) cannot
 * trigger lazy loading or couple to the schema. Terminology follows
 * {@link PolicyVersionObservation} /
 * {@link PolicyVersionObservationOutcome}.
 *
 * <p><b>SHA-256 vs SimHash invariant:</b> SHA-256 remains authoritative for
 * exact unchanged detection. A {@code NEW_VERSION} is created iff the SHA-256
 * differs; SimHash never changes whether a version is created and never
 * converts a changed hash into {@code UNCHANGED}.
 *
 * @param policyId identifier of the observed policy
 * @param outcome detection outcome (FIRST_VERSION / UNCHANGED / NEW_VERSION)
 * @param versionNumber 1-based sequence of the created or current version
 * @param contentHash SHA-256 hash of the normalized content
 * @param diff textual difference between the previous and new versions;
 *             present only for {@code NEW_VERSION}, empty for
 *             {@code FIRST_VERSION} (no previous version) and
 *             {@code UNCHANGED} (no new version, diff engine not run)
 * @param similarity numerical SimHash similarity signal; present only for
 *                   {@code NEW_VERSION} when a previous version exists and
 *                   is derived from the persisted normalized canonical
 *                   contents via {@link com.soubhagya.policyimpactengine.diff.PolicySimHash}
 *                   and {@link com.soubhagya.policyimpactengine.diff.SimHashDistance};
 *                   empty for {@code FIRST_VERSION} (no previous version) and
 *                   {@code UNCHANGED} (SimHash not run); never persisted
 */
public record PolicyObservationResult(
		UUID policyId,
		PolicyVersionObservationOutcome outcome,
		int versionNumber,
		String contentHash,
		Optional<PolicyDiffResult> diff,
		Optional<SimHashSimilarity> similarity) {

	public PolicyObservationResult {
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		if (outcome == null) {
			throw new IllegalArgumentException("Outcome must not be null");
		}
		if (versionNumber < 1) {
			throw new IllegalArgumentException("Version number must be >= 1");
		}
		if (contentHash == null || contentHash.isBlank()) {
			throw new IllegalArgumentException("Content hash must not be blank");
		}
		if (diff == null) {
			throw new IllegalArgumentException("Diff must not be null (use Optional.empty() when absent)");
		}
		if (similarity == null) {
			throw new IllegalArgumentException("Similarity must not be null (use Optional.empty() when absent)");
		}
	}
}
