package com.soubhagya.policyimpactengine.policy.application;

import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;

/**
 * Application-level result of a policy content observation.
 *
 * <p>Carries the detection outcome together with the relevant version:
 * the newly created version for {@code FIRST_VERSION}/{@code NEW_VERSION},
 * or the existing current version for {@code UNCHANGED}.
 */
public record PolicyVersionObservation(PolicyVersionObservationOutcome outcome, PolicyVersion version) {

	public PolicyVersionObservation {
		if (outcome == null) {
			throw new IllegalArgumentException("Outcome must not be null");
		}
		if (version == null) {
			throw new IllegalArgumentException("Version must not be null");
		}
	}

}
