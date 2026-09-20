package com.soubhagya.policyimpactengine.monitoring.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;

/**
 * Phase 2U.1 — bounded exponential backoff with equal jitter for transient
 * observation failures.
 *
 * <p>Pure computation over explicit inputs: given the just-failed attempt's
 * number, the failure classification, and the failure time, it answers how
 * long to wait before the retry and therefore what the policy's next check
 * time becomes. Retries are <em>scheduled</em> through
 * {@code policy.next_check_at} and performed later as ordinary observations
 * through the single shared claim path — no retry row is ever held open as
 * {@code PENDING} across a backoff window, and no thread ever sleeps.
 *
 * <p>Delay rule: {@code delay(n) = min(cap, base · multiplier^(n-1))} with
 * equal jitter {@code sleep = delay/2 + uniform(0, delay/2)}. Jitter comes
 * from the injected {@link Random} (unseeded in production, seeded in
 * tests), so every delay is exactly reproducible in tests. When the failed
 * attempt exhausts {@code maxAttempts}, or the failure is permanent, the
 * next check falls back to the regular check interval and the retry chain
 * ends.
 */
@Component
public class RetryPolicy {

	private final int maxAttempts;
	private final Duration baseDelay;
	private final double multiplier;
	private final Duration maxDelay;
	private final Duration checkInterval;
	private final Random jitter;

	public RetryPolicy(
			@Value("${monitoring.retry-max-attempts:5}") int maxAttempts,
			@Value("${monitoring.retry-base-delay:PT5M}") Duration baseDelay,
			@Value("${monitoring.retry-multiplier:2.0}") double multiplier,
			@Value("${monitoring.retry-max-delay:PT6H}") Duration maxDelay,
			@Value("${monitoring.check-interval:PT24H}") Duration checkInterval,
			Random jitter) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("Max attempts must be >= 1");
		}
		if (baseDelay == null || baseDelay.isZero() || baseDelay.isNegative()) {
			throw new IllegalArgumentException("Base delay must be a positive duration");
		}
		if (Double.isNaN(multiplier) || multiplier < 1.0) {
			throw new IllegalArgumentException("Multiplier must be >= 1.0");
		}
		if (maxDelay == null || maxDelay.isZero() || maxDelay.isNegative()) {
			throw new IllegalArgumentException("Max delay must be a positive duration");
		}
		if (maxDelay.compareTo(baseDelay) < 0) {
			throw new IllegalArgumentException("Max delay must be >= base delay");
		}
		if (checkInterval == null || checkInterval.isZero() || checkInterval.isNegative()) {
			throw new IllegalArgumentException("Check interval must be a positive duration");
		}
		if (jitter == null) {
			throw new IllegalArgumentException("Jitter source must not be null");
		}
		this.maxAttempts = maxAttempts;
		this.baseDelay = baseDelay;
		this.multiplier = multiplier;
		this.maxDelay = maxDelay;
		this.checkInterval = checkInterval;
		this.jitter = jitter;
	}

	/**
	 * Whether attempt {@code attemptNumber}, having just failed
	 * transiently, earns another retry ({@code attemptNumber < maxAttempts}).
	 */
	public boolean retriesRemaining(int attemptNumber) {
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("Attempt number must be >= 1");
		}
		return attemptNumber < maxAttempts;
	}

	/**
	 * The jittered backoff before the retry following failed attempt
	 * {@code attemptNumber}: within {@code [delay/2, delay)} where
	 * {@code delay = min(cap, base · multiplier^(n-1))}.
	 */
	public Duration backoffDelay(int attemptNumber) {
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("Attempt number must be >= 1");
		}
		double grown = baseDelay.toMillis() * Math.pow(multiplier, attemptNumber - 1);
		long capped = Math.min(maxDelay.toMillis(), (long) grown);
		long half = capped / 2;
		long sleepMillis = half + (long) (jitter.nextDouble() * (capped - half));
		return Duration.ofMillis(sleepMillis);
	}

	/**
	 * The policy's next check time after a failure at {@code failureTime}:
	 * the jittered backoff for a transient failure with retries remaining,
	 * otherwise the regular check interval (permanent failure or an
	 * exhausted chain ends the streak and resumes normal cadence).
	 */
	public Instant nextCheckAt(Instant failureTime, PolicyFetchFailureKind kind, int attemptNumber) {
		if (failureTime == null) {
			throw new IllegalArgumentException("Failure time must not be null");
		}
		if (kind == null) {
			throw new IllegalArgumentException("Failure kind must not be null");
		}
		if (kind == PolicyFetchFailureKind.TRANSIENT && retriesRemaining(attemptNumber)) {
			return failureTime.plus(backoffDelay(attemptNumber));
		}
		return failureTime.plus(checkInterval);
	}

	int maxAttempts() {
		return maxAttempts;
	}
}
