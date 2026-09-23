package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Phase 13-C — pure unit tests for {@link TokenBucket} (see DECISIONS.md
 * ADR-025). No Spring context, no database: instants are passed
 * explicitly, mirroring the fixed clocks used in production tests.
 */
class TokenBucketTest {

	private static final Instant START = Instant.parse("2026-09-23T10:00:00Z");

	@Test
	void consumesUpToCapacityThenRejects() {
		TokenBucket bucket = new TokenBucket(3, Duration.ofMinutes(1), START);

		assertThat(bucket.tryConsume(START).allowed()).isTrue();
		assertThat(bucket.tryConsume(START).allowed()).isTrue();
		assertThat(bucket.tryConsume(START).allowed()).isTrue();

		TokenBucket.Verdict rejected = bucket.tryConsume(START);
		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void refillsProRataOverTime() {
		TokenBucket bucket = new TokenBucket(60, Duration.ofMinutes(1), START);
		for (int i = 0; i < 60; i++) {
			assertThat(bucket.tryConsume(START).allowed()).isTrue();
		}
		assertThat(bucket.tryConsume(START).allowed()).isFalse();

		assertThat(bucket.tryConsume(START.plusSeconds(1)).allowed()).isTrue();
		assertThat(bucket.tryConsume(START.plusSeconds(1)).allowed()).isFalse();

		for (int i = 0; i < 30; i++) {
			assertThat(bucket.tryConsume(START.plusSeconds(31)).allowed()).isTrue();
		}
		assertThat(bucket.tryConsume(START.plusSeconds(31)).allowed()).isFalse();
	}

	@Test
	void fullWindowRestoresFullCapacity() {
		TokenBucket bucket = new TokenBucket(2, Duration.ofMinutes(1), START);
		bucket.tryConsume(START);
		bucket.tryConsume(START);

		assertThat(bucket.tryConsume(START.plusSeconds(60)).allowed()).isTrue();
		assertThat(bucket.tryConsume(START.plusSeconds(60)).allowed()).isTrue();
		assertThat(bucket.tryConsume(START.plusSeconds(60)).allowed()).isFalse();
	}

	@Test
	void retryAfterCountsUpToNextToken() {
		TokenBucket bucket = new TokenBucket(1, Duration.ofMinutes(1), START);
		assertThat(bucket.tryConsume(START).allowed()).isTrue();

		TokenBucket.Verdict rejected = bucket.tryConsume(START);
		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.retryAfterSeconds()).isEqualTo(60);
	}

	@Test
	void allowancesCarryZeroRetryAfter() {
		TokenBucket bucket = new TokenBucket(5, Duration.ofMinutes(1), START);

		assertThat(bucket.tryConsume(START).retryAfterSeconds()).isZero();
	}

	@Test
	void negativeClockStepGrantsNothing() {
		TokenBucket bucket = new TokenBucket(1, Duration.ofMinutes(1), START);
		assertThat(bucket.tryConsume(START).allowed()).isTrue();

		assertThat(bucket.tryConsume(START.minusSeconds(30)).allowed()).isFalse();
		assertThat(bucket.tryConsume(START).allowed()).isFalse();
	}

	@Test
	void idleTrackingFollowsLastDecision() {
		TokenBucket bucket = new TokenBucket(5, Duration.ofMinutes(1), START);

		assertThat(bucket.isIdleSince(START.plus(Duration.ofMinutes(9)),
				Duration.ofMinutes(10))).isFalse();
		assertThat(bucket.isIdleSince(START.plus(Duration.ofMinutes(11)),
				Duration.ofMinutes(10))).isTrue();

		bucket.tryConsume(START.plus(Duration.ofMinutes(11)));

		assertThat(bucket.isIdleSince(START.plus(Duration.ofMinutes(20)),
				Duration.ofMinutes(10))).isFalse();
	}

	@Test
	void constructorRejectsInvalidArguments() {
		assertThatThrownBy(() -> new TokenBucket(0, Duration.ofMinutes(1), START))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TokenBucket(1, Duration.ZERO, START))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TokenBucket(1, Duration.ofMinutes(1), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TokenBucket(1, Duration.ofMinutes(1), START)
				.tryConsume(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
