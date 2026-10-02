package com.soubhagya.policyimpactengine.policy.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;

/**
 * Phase 16-C — read-model row of a policy's persisted check history
 * (see DECISIONS.md ADR-035). Rendered only from the attempt row's own
 * scalars: trigger and status verbatim, {@code failureKind} and
 * {@code errorMessage} present only on {@code FAILED} rows,
 * {@code durationMs} and {@code completedAt} null while the attempt is
 * still live. The lazy {@code policy} association is never read, and
 * no rescheduling state (which lives on the policy row, not the
 * attempt) is exposed.
 */
public record PolicyCheckHistoryResponse(

		UUID id,
		String trigger,
		int attemptNumber,
		String status,
		String failureKind,
		Integer httpStatus,
		Long bytesFetched,
		Long durationMs,
		String errorMessage,
		Instant startedAt,
		Instant completedAt

) {

	public static PolicyCheckHistoryResponse from(PolicyFetchAttempt attempt) {
		if (attempt == null) {
			throw new IllegalArgumentException("Attempt must not be null");
		}
		return new PolicyCheckHistoryResponse(
				attempt.getId(),
				attempt.getTrigger().name(),
				attempt.getAttemptNumber(),
				attempt.getStatus().name(),
				attempt.getFailureKind() == null ? null : attempt.getFailureKind().name(),
				attempt.getHttpStatus(),
				attempt.getBytesFetched(),
				attempt.getDurationMs(),
				attempt.getErrorMessage(),
				attempt.getStartedAt(),
				attempt.getCompletedAt());
	}
}
