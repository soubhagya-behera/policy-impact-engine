package com.soubhagya.policyimpactengine.monitoring.domain;

/**
 * Phase 2S — lifecycle status of a policy observation attempt.
 *
 * <p>{@code PENDING} is part of the vocabulary for the scheduler phase
 * (atomic work claiming); Phase 2S writes {@code IN_PROGRESS} directly
 * because observations run synchronously with no claiming queue.
 */
public enum PolicyFetchAttemptStatus {

	/** Claimed but not yet started (reserved for the scheduler phase). */
	PENDING,

	/** The check is running. */
	IN_PROGRESS,

	/** The check completed and its outcome was persisted. */
	SUCCESS,

	/** The check failed; {@code error_message} carries the cause. */
	FAILED,

	/** The check completed with identical content; no new version was created. */
	SKIPPED_UNCHANGED

}
