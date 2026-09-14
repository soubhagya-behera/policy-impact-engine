package com.soubhagya.policyimpactengine.policy.domain;

/**
 * Lifecycle state of a registered policy.
 */
public enum PolicyStatus {

	/** Registered and expected to be checked by the monitoring pipeline (Phase 9). */
	ACTIVE,

	/** Retained but no longer monitored. */
	ARCHIVED

}
