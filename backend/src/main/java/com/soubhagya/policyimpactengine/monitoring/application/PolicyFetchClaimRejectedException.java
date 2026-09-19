package com.soubhagya.policyimpactengine.monitoring.application;

import java.util.UUID;

/**
 * Phase 2U — signals that a policy check could not be claimed because
 * another {@code PENDING} or {@code IN_PROGRESS} attempt already owns the
 * policy.
 *
 * <p>No fetch was performed and no second runnable attempt was created: the
 * V11 partial unique index admitted exactly one non-terminal attempt and the
 * conditional claim gave it to the other trigger. MANUAL callers treat this
 * as an explicit rejection; the scheduler treats it as a skip signal for
 * the current cycle. No retry or re-enqueue happens here; that belongs to
 * Phase 2U.1.
 */
public class PolicyFetchClaimRejectedException extends RuntimeException {

	public PolicyFetchClaimRejectedException(UUID policyId) {
		super("Policy " + policyId + " already has an in-flight check; refusing duplicate work");
	}

	public PolicyFetchClaimRejectedException(UUID policyId, Throwable cause) {
		super("Policy " + policyId + " already has an in-flight check; refusing duplicate work", cause);
	}
}
