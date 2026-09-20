package com.soubhagya.policyimpactengine.monitoring;

import java.time.Clock;
import java.util.Random;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 2S/2T — monitoring module wiring.
 *
 * <p>Exposes the {@link Clock} used for attempt timestamps and
 * whole-observation durations. Production uses the system clock; tests
 * inject fixed or sequenced clocks directly, so durations stay
 * deterministic without wall-clock dependence.
 *
 * <p>Phase 2T enables scheduling for the single-threaded sequential
 * observation tick. No scheduler pool is configured: Spring's default
 * single-threaded scheduler guarantees ticks never overlap, which is the
 * Phase 2T concurrency model (cross-instance guarantees belong to the
 * follow-up claiming slice).
 */
@Configuration
@EnableScheduling
public class MonitoringConfiguration {

	@Bean
	public Clock observationClock() {
		return Clock.systemUTC();
	}

	/**
	 * Phase 2U.1 — jitter source for retry backoff.
	 *
	 * <p>Unseeded in production; tests construct the retry policy directly
	 * with a seeded {@link Random}, so every backoff delay is exactly
	 * reproducible without wall-clock or randomness dependence.
	 */
	@Bean
	public Random retryJitter() {
		return new Random();
	}
}
