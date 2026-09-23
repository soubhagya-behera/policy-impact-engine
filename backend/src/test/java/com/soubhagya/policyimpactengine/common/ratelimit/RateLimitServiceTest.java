package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Phase 13-C — unit tests for {@link RateLimitService} (see DECISIONS.md
 * ADR-025). A fixed {@link Clock} drives refill deterministically; no
 * Spring context, no database.
 */
class RateLimitServiceTest {

	private static final Instant START = Instant.parse("2026-09-23T10:00:00Z");

	private static RateLimitProperties properties() {
		return new RateLimitProperties(true,
				Duration.ofMinutes(1), 3,
				Duration.ofMinutes(1), 5,
				Duration.ofMinutes(1), 2,
				Duration.ofMinutes(1), 4,
				1000, Duration.ofMinutes(10));
	}

	private static RateLimitService service(Clock clock) {
		return new RateLimitService(properties(), clock);
	}

	@Test
	void tiersEnforceTheirOwnLimitsPerKey() {
		RateLimitService service = service(Clock.fixed(START, ZoneOffset.UTC));
		UUID user = UUID.randomUUID();

		for (int i = 0; i < 5; i++) {
			assertThat(service.apiRequest(user).allowed()).isTrue();
		}
		RateLimitService.Decision rejected = service.apiRequest(user);
		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1);

		assertThat(service.apiRequest(UUID.randomUUID()).allowed()).isTrue();
		assertThat(service.explanationRequest(user).allowed()).isTrue();
	}

	@Test
	void registerAndLoginBucketsAreSeparatePerEndpointAndKeyedByIp() {
		RateLimitService service = service(Clock.fixed(START, ZoneOffset.UTC));

		for (int i = 0; i < 3; i++) {
			assertThat(service.registerAttempt("203.0.113.7").allowed()).isTrue();
		}
		assertThat(service.registerAttempt("203.0.113.7").allowed()).isFalse();
		assertThat(service.registerAttempt("203.0.113.8").allowed()).isTrue();

		assertThat(service.loginAttempt("203.0.113.7").allowed()).isTrue();
		for (int i = 0; i < 2; i++) {
			assertThat(service.loginAttempt("203.0.113.7").allowed()).isTrue();
		}
		assertThat(service.loginAttempt("203.0.113.7").allowed()).isFalse();

		for (int i = 0; i < 4; i++) {
			assertThat(service.anonymousRequest("203.0.113.9").allowed()).isTrue();
		}
		assertThat(service.anonymousRequest("203.0.113.9").allowed()).isFalse();
	}

	@Test
	void windowExpiryRestoresCapacity() {
		Clock start = Clock.fixed(START, ZoneOffset.UTC);
		RateLimitService service = service(start);
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 5; i++) {
			service.apiRequest(user);
		}
		assertThat(service.apiRequest(user).allowed()).isFalse();

		RateLimitService later = new RateLimitService(properties(),
				Clock.fixed(START.plus(Duration.ofMinutes(1)), ZoneOffset.UTC));
		for (int i = 0; i < 5; i++) {
			assertThat(later.apiRequest(user).allowed()).isTrue();
		}
	}

	@Test
	void idleBucketsAreEvictedLazily() {
		RateLimitProperties props = new RateLimitProperties(true,
				Duration.ofMinutes(1), 3,
				Duration.ofMinutes(1), 5,
				Duration.ofMinutes(1), 2,
				Duration.ofMinutes(1), 4,
				3, Duration.ofMinutes(10));
		ManualClock clock = new ManualClock();
		RateLimitService service = new RateLimitService(props, clock);
		service.apiRequest(UUID.randomUUID());
		service.apiRequest(UUID.randomUUID());
		assertThat(service.trackedKeys()).isEqualTo(2);

		clock.advance(Duration.ofMinutes(11));
		service.apiRequest(UUID.randomUUID());
		service.apiRequest(UUID.randomUUID());
		service.apiRequest(UUID.randomUUID());
		assertThat(service.trackedKeys()).isLessThanOrEqualTo(3);
	}

	@Test
	void trackedKeysStayBounded() {
		RateLimitProperties props = new RateLimitProperties(true,
				Duration.ofMinutes(1), 100,
				Duration.ofMinutes(1), 100,
				Duration.ofMinutes(1), 100,
				Duration.ofMinutes(1), 100,
				5, Duration.ofMinutes(10));
		RateLimitService service = new RateLimitService(props,
				Clock.fixed(START, ZoneOffset.UTC));
		for (int i = 0; i < 50; i++) {
			assertThat(service.registerAttempt("10.0.0." + i).allowed()).isTrue();
		}
		assertThat(service.trackedKeys()).isLessThanOrEqualTo(5);
	}

	@Test
	void concurrentConsumesForOneKeyCountExactly() throws Exception {
		RateLimitService service = service(Clock.fixed(START, ZoneOffset.UTC));
		UUID user = UUID.randomUUID();
		int threads = 10;
		int perThread = 5;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger allowed = new AtomicInteger();
		try {
			for (int t = 0; t < threads; t++) {
				pool.submit(() -> {
					ready.countDown();
					try {
						go.await(10, TimeUnit.SECONDS);
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						return;
					}
					for (int i = 0; i < perThread; i++) {
						if (service.explanationRequest(user).allowed()) {
							allowed.incrementAndGet();
						}
					}
				});
			}
			ready.await(10, TimeUnit.SECONDS);
			go.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(allowed.get()).isEqualTo(2);
	}

	@Test
	void constructorAndKeysRejectInvalidArguments() {
		assertThatThrownBy(() -> new RateLimitService(null,
				Clock.fixed(START, ZoneOffset.UTC)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitService(properties(), null))
				.isInstanceOf(IllegalArgumentException.class);

		RateLimitService service = service(Clock.fixed(START, ZoneOffset.UTC));
		assertThatThrownBy(() -> service.registerAttempt("  "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.loginAttempt("  "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.apiRequest(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.explanationRequest(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.anonymousRequest(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void propertiesRejectNonPositiveValues() {
		assertThatThrownBy(() -> new RateLimitProperties(true,
				Duration.ZERO, 3,
				Duration.ofMinutes(1), 5,
				Duration.ofMinutes(1), 2,
				Duration.ofMinutes(1), 4,
				1000, Duration.ofMinutes(10)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitProperties(true,
				Duration.ofMinutes(1), 0,
				Duration.ofMinutes(1), 5,
				Duration.ofMinutes(1), 2,
				Duration.ofMinutes(1), 4,
				1000, Duration.ofMinutes(10)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitProperties(true,
				Duration.ofMinutes(1), 3,
				Duration.ofMinutes(1), 5,
				Duration.ofMinutes(1), 2,
				Duration.ofMinutes(1), 4,
				0, Duration.ofMinutes(10)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** Manually advanced clock for idle-purge testing. */
	private static final class ManualClock extends Clock {

		private Instant now = START;

		void advance(Duration step) {
			now = now.plus(step);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
