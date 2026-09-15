package com.soubhagya.policyimpactengine.policy.application;

import java.util.UUID;

/**
 * Application-level result of a policy observation.
 *
 * <p>Carries only scalar data — never JPA entities — so callers (and any
 * future web layer) cannot trigger lazy loading or couple to the schema.
 * Terminology follows {@link PolicyVersionObservation} /
 * {@link PolicyVersionObservationOutcome}.
 *
 * @param policyId identifier of the observed policy
 * @param outcome detection outcome (FIRST_VERSION / UNCHANGED / NEW_VERSION)
 * @param versionNumber 1-based sequence of the created or current version
 * @param contentHash SHA-256 hash of the normalized content
 */
public record PolicyObservationResult(
		UUID policyId,
		PolicyVersionObservationOutcome outcome,
		int versionNumber,
		String contentHash) {

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
	}
}
