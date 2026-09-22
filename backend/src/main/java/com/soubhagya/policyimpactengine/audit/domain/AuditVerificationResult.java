package com.soubhagya.policyimpactengine.audit.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 11B — immutable verdict of a read-only audit chain
 * verification (see DECISIONS.md ADR-023 §10).
 *
 * <p>Either VALID (every visited event recomputed cleanly, including
 * the empty chain with {@code verifiedCount == 0}) or INVALID with
 * exactly one deterministic first failure: the offending event id,
 * its zero-based chain position when defined, and the reason. The
 * result carries no metadata, resource, actor, or timestamp contents
 * — only the position, id, and reason needed to locate the break.
 */
public final class AuditVerificationResult {

	/**
	 * Deterministic failure categories, listed in verification
	 * precedence order: malformed values first, then genesis
	 * structure, then the earliest chain-order failure, then
	 * off-chain rows.
	 */
	public enum FailureReason {
		MALFORMED_HASH,
		MULTIPLE_GENESIS,
		MISSING_GENESIS,
		SHARED_PREDECESSOR,
		HASH_MISMATCH,
		BROKEN_LINK,
		MISSING_PREDECESSOR,
		UNREACHABLE_ROW
	}

	private final boolean valid;
	private final long verifiedCount;
	private final UUID failedEventId;
	private final Integer failedPosition;
	private final FailureReason failureReason;

	private AuditVerificationResult(boolean valid, long verifiedCount, UUID failedEventId,
			Integer failedPosition, FailureReason failureReason) {
		this.valid = valid;
		this.verifiedCount = verifiedCount;
		this.failedEventId = failedEventId;
		this.failedPosition = failedPosition;
		this.failureReason = failureReason;
	}

	/**
	 * Returns a valid verdict for {@code verifiedCount} verified
	 * events. An empty chain is valid with count zero: a fresh
	 * installation with no audit history.
	 */
	public static AuditVerificationResult valid(long verifiedCount) {
		if (verifiedCount < 0) {
			throw new IllegalArgumentException("Verified count must not be negative");
		}
		return new AuditVerificationResult(true, verifiedCount, null, null, null);
	}

	/**
	 * Returns an invalid verdict naming the first failing event.
	 * {@code failedPosition} is null when no chain position applies
	 * (pre-walk structural failures and off-chain rows).
	 */
	public static AuditVerificationResult invalid(long verifiedCount, UUID failedEventId,
			Integer failedPosition, FailureReason failureReason) {
		if (verifiedCount < 0) {
			throw new IllegalArgumentException("Verified count must not be negative");
		}
		if (failedEventId == null) {
			throw new IllegalArgumentException("Failed event id must not be null");
		}
		if (failureReason == null) {
			throw new IllegalArgumentException("Failure reason must not be null");
		}
		if (failedPosition != null && failedPosition < 0) {
			throw new IllegalArgumentException("Failed position must not be negative");
		}
		return new AuditVerificationResult(false, verifiedCount, failedEventId,
				failedPosition, failureReason);
	}

	public boolean isValid() {
		return valid;
	}

	public long getVerifiedCount() {
		return verifiedCount;
	}

	public Optional<UUID> getFailedEventId() {
		return Optional.ofNullable(failedEventId);
	}

	public Optional<Integer> getFailedPosition() {
		return Optional.ofNullable(failedPosition);
	}

	public Optional<FailureReason> getFailureReason() {
		return Optional.ofNullable(failureReason);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof AuditVerificationResult that)) {
			return false;
		}
		return valid == that.valid
				&& verifiedCount == that.verifiedCount
				&& Objects.equals(failedEventId, that.failedEventId)
				&& Objects.equals(failedPosition, that.failedPosition)
				&& failureReason == that.failureReason;
	}

	@Override
	public int hashCode() {
		return Objects.hash(valid, verifiedCount, failedEventId, failedPosition,
				failureReason);
	}

	@Override
	public String toString() {
		if (valid) {
			return "AuditVerificationResult{VALID, verifiedCount=" + verifiedCount + "}";
		}
		return "AuditVerificationResult{INVALID, verifiedCount=" + verifiedCount
				+ ", failedEventId=" + failedEventId
				+ ", failedPosition=" + failedPosition
				+ ", failureReason=" + failureReason + "}";
	}
}
