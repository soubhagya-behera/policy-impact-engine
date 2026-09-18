package com.soubhagya.policyimpactengine.monitoring;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 2S — monitoring module wiring.
 *
 * <p>Exposes the {@link Clock} used for attempt timestamps and
 * whole-observation durations. Production uses the system clock; tests
 * inject fixed or sequenced clocks directly, so durations stay
 * deterministic without wall-clock dependence.
 */
@Configuration
public class MonitoringConfiguration {

	@Bean
	public Clock observationClock() {
		return Clock.systemUTC();
	}
}
