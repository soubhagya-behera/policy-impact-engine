package com.soubhagya.policyimpactengine.policy.application;

/**
 * Outcome of observing already-processed policy content for exact
 * hash-based change detection.
 */
public enum PolicyVersionObservationOutcome {

	/** First observed content for the policy; version 1 was created. */
	FIRST_VERSION,

	/** Supplied hash equals the latest version's hash; no version created. */
	UNCHANGED,

	/** Supplied hash differs from the latest version; a new version was created. */
	NEW_VERSION

}
