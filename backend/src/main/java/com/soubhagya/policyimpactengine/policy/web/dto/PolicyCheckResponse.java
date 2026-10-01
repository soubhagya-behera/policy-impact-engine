package com.soubhagya.policyimpactengine.policy.web.dto;

import java.util.UUID;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome;

/**
 * Phase 16-B/2 — read-model result of a synchronous manual check (see
 * DECISIONS.md ADR-034). Derived only from the existing
 * {@link PolicyObservationResult} plus the terminal attempt status that
 * {@code observe()} guarantees ({@code UNCHANGED} always completes as
 * {@code SKIPPED_UNCHANGED}, every other outcome as {@code SUCCESS});
 * no new persistence, no attempt id, no entities.
 */
public record PolicyCheckResponse(

		UUID policyId,
		String outcome,
		int versionNumber,
		String contentHash,
		int changeCount,
		String attemptStatus

) {

	public static PolicyCheckResponse from(PolicyObservationResult result) {
		if (result == null) {
			throw new IllegalArgumentException("Observation result must not be null");
		}
		int changeCount = result.diff().map(diff -> diff.changes().size()).orElse(0);
		String attemptStatus = result.outcome() == PolicyVersionObservationOutcome.UNCHANGED
				? PolicyFetchAttemptStatus.SKIPPED_UNCHANGED.name()
				: PolicyFetchAttemptStatus.SUCCESS.name();
		return new PolicyCheckResponse(
				result.policyId(),
				result.outcome().name(),
				result.versionNumber(),
				result.contentHash(),
				changeCount,
				attemptStatus);
	}
}
