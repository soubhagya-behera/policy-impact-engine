package com.soubhagya.policyimpactengine.monitoring.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.policy.domain.Policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Phase 2S — one recorded policy observation check.
 *
 * <p>History, not state: rows are never deleted, and concurrent observations
 * of one policy each record their own row. Ownership is derived through
 * {@code policy_id} → {@code policy.id}; the row stores no duplicated
 * {@code user_id} (V3/V4/V7/V8 no-duplicated-owner-FK convention).
 *
 * <p>Lifecycle mutability is explicitly sanctioned and narrow (see ADR-010):
 * the row is created {@code IN_PROGRESS} and transitions exactly once to a
 * terminal status ({@code SUCCESS}, {@code FAILED},
 * {@code SKIPPED_UNCHANGED}). Only {@code status}, {@code httpStatus},
 * {@code bytesFetched}, {@code durationMs}, {@code errorMessage}, and
 * {@code completedAt} are set by that single transition — and the payload
 * fields among them ({@code httpStatus}, {@code bytesFetched}) are
 * write-once in effect because the transition itself is guarded to run
 * exactly once. {@code policy}, {@code trigger}, {@code attemptNumber}, and
 * {@code startedAt} are strictly write-once ({@code updatable = false}).
 * There are no other mutation paths: no setters.
 *
 * <p>Phase 2U atomic claiming uses {@link #pending} plus a conditional claim
 * update; see that factory for the claim-time {@code startedAt} rule.
 */
@Entity
@Table(name = "policy_fetch_attempt")
@Getter
public class PolicyFetchAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "policy_id", nullable = false, updatable = false)
	private Policy policy;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 16)
	private PolicyFetchAttemptTrigger trigger;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = true, length = 32)
	private PolicyFetchAttemptStatus status;

	@Column(name = "http_status", nullable = true, updatable = true)
	private Integer httpStatus;

	@Column(name = "bytes_fetched", nullable = true, updatable = true)
	private Long bytesFetched;

	@Column(name = "duration_ms", nullable = true, updatable = true)
	private Long durationMs;

	@Column(name = "error_message", nullable = true, updatable = true, columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "attempt_number", nullable = false, updatable = false)
	private int attemptNumber;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "completed_at", nullable = true, updatable = true)
	private Instant completedAt;

	protected PolicyFetchAttempt() {
		// Required by JPA.
	}

	/**
	 * Starts an in-progress attempt. Every Phase 2S attempt uses
	 * {@code attemptNumber = 1}; retry numbering belongs to the scheduler
	 * phase.
	 */
	public PolicyFetchAttempt(Policy policy, PolicyFetchAttemptTrigger trigger, int attemptNumber,
			Instant startedAt) {
		if (policy == null) {
			throw new IllegalArgumentException("Policy must not be null");
		}
		if (trigger == null) {
			throw new IllegalArgumentException("Trigger must not be null");
		}
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("Attempt number must be >= 1");
		}
		if (startedAt == null) {
			throw new IllegalArgumentException("Started at must not be null");
		}
		this.policy = policy;
		this.trigger = trigger;
		this.status = PolicyFetchAttemptStatus.IN_PROGRESS;
		this.attemptNumber = attemptNumber;
		this.startedAt = startedAt;
	}

	/**
	 * Creates a pending claim candidate for Phase 2U atomic work claiming.
	 * The row is not runnable until the claim service promotes it with the
	 * conditional {@code UPDATE ... WHERE status = 'PENDING'}; at most one
	 * {@code PENDING}/{@code IN_PROGRESS} row per policy may exist
	 * (V11 partial unique index). The claim stamps {@code startedAt} at
	 * claim time, so the provisional creation timestamp given here is
	 * replaced when work actually starts. Phase 2U attempts use
	 * {@code attemptNumber = 1}; retry numbering belongs to Phase 2U.1.
	 */
	public static PolicyFetchAttempt pending(Policy policy, PolicyFetchAttemptTrigger trigger,
			int attemptNumber, Instant startedAt) {
		PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy, trigger, attemptNumber, startedAt);
		attempt.status = PolicyFetchAttemptStatus.PENDING;
		return attempt;
	}

	/**
	 * Performs the single sanctioned terminal transition from
	 * {@code PENDING}/{@code IN_PROGRESS} to {@code SUCCESS},
	 * {@code FAILED}, or {@code SKIPPED_UNCHANGED}, stamping the completion
	 * time, the non-negative whole-observation duration, and the fetch
	 * metadata known at completion. Any second transition — or any
	 * non-terminal target — is rejected.
	 */
	public void complete(PolicyFetchAttemptStatus terminal, Integer httpStatus, Long bytesFetched,
			String errorMessage, Instant completedAt) {
		if (terminal == null) {
			throw new IllegalArgumentException("Terminal status must not be null");
		}
		if (terminal != PolicyFetchAttemptStatus.SUCCESS
				&& terminal != PolicyFetchAttemptStatus.FAILED
				&& terminal != PolicyFetchAttemptStatus.SKIPPED_UNCHANGED) {
			throw new IllegalArgumentException("Terminal status must be SUCCESS, FAILED, or SKIPPED_UNCHANGED");
		}
		if (status != PolicyFetchAttemptStatus.PENDING && status != PolicyFetchAttemptStatus.IN_PROGRESS) {
			throw new IllegalStateException("Attempt is already terminal: " + status);
		}
		if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
			throw new IllegalArgumentException("HTTP status must be between 100 and 599");
		}
		if (bytesFetched != null && bytesFetched < 0) {
			throw new IllegalArgumentException("Bytes fetched must be >= 0");
		}
		if (completedAt == null) {
			throw new IllegalArgumentException("Completed at must not be null");
		}
		this.status = terminal;
		this.httpStatus = httpStatus;
		this.bytesFetched = bytesFetched;
		this.errorMessage = errorMessage;
		this.completedAt = completedAt;
		this.durationMs = Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
	}
}
