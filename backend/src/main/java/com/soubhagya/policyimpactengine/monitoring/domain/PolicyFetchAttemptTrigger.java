package com.soubhagya.policyimpactengine.monitoring.domain;

/**
 * Phase 2S — what triggered a policy observation check.
 *
 * <p>Part of the schema/domain vocabulary. Only {@code MANUAL} is exercised
 * in Phase 2S ({@code PolicyObservationService.observe} records it);
 * {@code SCHEDULED} is reserved for the scheduler phase and must not gain
 * behavior until then.
 */
public enum PolicyFetchAttemptTrigger {

	/** A direct observation call (the only trigger in Phase 2S). */
	MANUAL,

	/** A scheduled monitoring check (reserved for the scheduler phase). */
	SCHEDULED

}
