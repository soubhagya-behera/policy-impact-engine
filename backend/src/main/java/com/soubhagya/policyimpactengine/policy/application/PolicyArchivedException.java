package com.soubhagya.policyimpactengine.policy.application;

import java.util.UUID;

/**
 * Phase 16-B/2 — signals that a manual policy check was refused because
 * the owned policy is {@code ARCHIVED} (see DECISIONS.md ADR-034).
 *
 * <p>No fetch was performed, no attempt row was created, and no status
 * changed: the caller should reactivate the policy first. Mapped to
 * HTTP 409 (state conflict), never 404 — the owner already sees the
 * archived status through the owner-scoped reads.
 */
public class PolicyArchivedException extends RuntimeException {

	public PolicyArchivedException(UUID policyId) {
		super("Policy " + policyId + " is archived; reactivate it before checking");
	}
}
