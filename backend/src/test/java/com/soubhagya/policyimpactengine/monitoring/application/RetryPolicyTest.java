package com.soubhagya.policyimpactengine.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;

/**
 * Phase 2U.1 — deterministic unit tests for bounded exponential backoff
 * with equal jitter.
 *
 * <p>No Spring context, no database, no network. Jitter comes from a
 * seeded {@link Random}, so every delay is exactly reproducible: tests
 * replay the same seed through a control generator and pin the exact
 * millisecond values, the cap, the jitter bounds, and the exhaustion
 * boundary.
 */
class RetryPolicyTest {

	private static final Instant FAILURE = Instant.parse("2026-09-18T10:00:00Z");

	private RetryPolicy policy() {
		return new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(7));
	}

	@Test
	void backoffGrowsExponentiallyWithExactSeededJitter() {
		RetryPolicy retry = policy();
		Random control = new Random(7);

		// n=1: delay = min(6h, 5m) = 300000ms; sleep in [150000, 300000).
		long first = 150_000L + (long) (control.nextDouble() * 150_000L);
		assertThat(retry.backoffDelay(1)).isEqualTo(Duration.ofMillis(first));
		// n=2: delay = 10m = 600000ms; sleep in [300000, 600000).
		long second = 300_000L + (long) (control.nextDouble() * 300_000L);
		assertThat(retry.backoffDelay(2)).isEqualTo(Duration.ofMillis(second));
		// n=3: delay = 20m = 1200000ms; sleep in [600000, 1200000).
		long third = 600_000L + (long) (control.nextDouble() * 600_000L);
		assertThat(retry.backoffDelay(3)).isEqualTo(Duration.ofMillis(third));
	}

	@Test
	void backoffIsCapped() {
		RetryPolicy retry = policy();

		// n=100 would grow without bound; the cap pins delay at 6h and
		// equal jitter keeps the sleep within [3h, 6h).
		Duration sleep = retry.backoffDelay(100);
		assertThat(sleep).isGreaterThanOrEqualTo(Duration.ofHours(3));
		assertThat(sleep).isLessThan(Duration.ofHours(6));
	}

	@Test
	void jitterNeverDropsBelowHalfTheUncappedDelay() {
		RetryPolicy retry = policy();

		for (int attempt = 1; attempt <= 8; attempt++) {
			Duration sleep = retry.backoffDelay(attempt);
			long uncapped = Math.min(Duration.ofHours(6).toMillis(),
					(long) (Duration.ofMinutes(5).toMillis() * Math.pow(2.0, attempt - 1)));
			assertThat(sleep.toMillis()).isGreaterThanOrEqualTo(uncapped / 2);
			assertThat(sleep.toMillis()).isLessThan(uncapped);
		}
	}

	@Test
	void retriesRemainUntilMaxAttempts() {
		RetryPolicy retry = policy();

		assertThat(retry.retriesRemaining(1)).isTrue();
		assertThat(retry.retriesRemaining(4)).isTrue();
		assertThat(retry.retriesRemaining(5)).isFalse();
		assertThat(retry.retriesRemaining(6)).isFalse();
	}

	@Test
	void transientFailureWithRetriesRemainingSchedulesBackoff() {
		RetryPolicy retry = policy();
		Random control = new Random(7);
		long expected = 300_000L + (long) (control.nextDouble() * 300_000L);

		assertThat(retry.nextCheckAt(FAILURE, PolicyFetchFailureKind.TRANSIENT, 2))
				.isEqualTo(FAILURE.plus(Duration.ofMillis(expected)));
	}

	@Test
	void exhaustedTransientChainFallsBackToCheckInterval() {
		RetryPolicy retry = policy();

		assertThat(retry.nextCheckAt(FAILURE, PolicyFetchFailureKind.TRANSIENT, 5))
				.isEqualTo(FAILURE.plus(Duration.ofHours(24)));
	}

	@Test
	void permanentFailureFallsBackToCheckInterval() {
		RetryPolicy retry = policy();

		assertThat(retry.nextCheckAt(FAILURE, PolicyFetchFailureKind.PERMANENT, 1))
				.isEqualTo(FAILURE.plus(Duration.ofHours(24)));
	}

	@Test
	void singleAttemptPolicyNeverRetries() {
		RetryPolicy retry = new RetryPolicy(1, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(7));

		assertThat(retry.retriesRemaining(1)).isFalse();
		assertThat(retry.nextCheckAt(FAILURE, PolicyFetchFailureKind.TRANSIENT, 1))
				.isEqualTo(FAILURE.plus(Duration.ofHours(24)));
	}

	@Test
	void invalidConfigurationRejected() {
		Random jitter = new Random(7);
		assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), jitter))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicy(5, Duration.ZERO, 2.0,
				Duration.ofHours(6), Duration.ofHours(24), jitter))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicy(5, Duration.ofMinutes(5), 0.5,
				Duration.ofHours(6), Duration.ofHours(24), jitter))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofMinutes(1), Duration.ofHours(24), jitter))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ZERO, jitter))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy().backoffDelay(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy().retriesRemaining(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy().nextCheckAt(null, PolicyFetchFailureKind.TRANSIENT, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy().nextCheckAt(FAILURE, null, 1))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
