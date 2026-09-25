package com.soubhagya.policyimpactengine.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockPropertySource;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 14-A/1 — binding and validation tests for the reserved
 * refresh rate-limit tier bound from {@code rate-limit.refresh-*}
 * (see DECISIONS.md ADR-029).
 *
 * <p>No Spring context, no database. Property binding runs through
 * Spring's {@link Binder} so {@code @DefaultValue} defaults resolve
 * exactly as they do at startup; positivity follows the existing
 * tier validation in {@link RateLimitProperties}. No service or
 * filter behavior attaches to this tier yet.
 */
class RateLimitRefreshPropertiesTest {

	@TestConfiguration
	@EnableConfigurationProperties(RateLimitProperties.class)
	static class DefaultsConfig {
	}

	private static RateLimitProperties bind(String... pairs) {
		MockPropertySource source = new MockPropertySource();
		for (int i = 0; i < pairs.length; i += 2) {
			source.setProperty(pairs[i], pairs[i + 1]);
		}
		Binder binder = new Binder(ConfigurationPropertySources.from(source));
		return binder.bind("rate-limit", RateLimitProperties.class)
				.orElseThrow(() -> new IllegalStateException("Binding must produce properties"));
	}

	/**
	 * Resolves {@link RateLimitProperties} with no properties set, so
	 * {@code @DefaultValue} defaults apply exactly as they do at
	 * startup. A bare {@link Binder} yields empty for an untouched
	 * prefix, hence the minimal runner context here.
	 */
	private static RateLimitProperties defaults() {
		AtomicReference<RateLimitProperties> bound = new AtomicReference<>();
		new ApplicationContextRunner()
				.withUserConfiguration(DefaultsConfig.class)
				.run(context -> bound.set(context.getBean(RateLimitProperties.class)));
		return bound.get();
	}

	@Test
	void refreshTierDefaultsBindCorrectly() {
		RateLimitProperties properties = defaults();

		assertThat(properties.refreshWindow()).isEqualTo(Duration.ofMinutes(1));
		assertThat(properties.refreshMaxRequests()).isEqualTo(10);
	}

	@Test
	void customRefreshTierValuesBindCorrectly() {
		RateLimitProperties properties = bind(
				"rate-limit.refresh-window", "PT5M",
				"rate-limit.refresh-max-requests", "25");

		assertThat(properties.refreshWindow()).isEqualTo(Duration.ofMinutes(5));
		assertThat(properties.refreshMaxRequests()).isEqualTo(25);
	}

	@Test
	void zeroRefreshWindowIsRejected() {
		assertThatThrownBy(() -> new RateLimitProperties(true,
						Duration.ofMinutes(1), 10,
						Duration.ofMinutes(1), 600,
						Duration.ofMinutes(1), 10,
						Duration.ofMinutes(1), 60,
						100000, Duration.ofMinutes(10),
						Duration.ZERO, 10))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("rate-limit.refresh-window must be positive");
	}

	@Test
	void negativeRefreshWindowIsRejected() {
		assertThatThrownBy(() -> new RateLimitProperties(true,
						Duration.ofMinutes(1), 10,
						Duration.ofMinutes(1), 600,
						Duration.ofMinutes(1), 10,
						Duration.ofMinutes(1), 60,
						100000, Duration.ofMinutes(10),
						Duration.ofMinutes(-1), 10))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("rate-limit.refresh-window must be positive");
	}

	@Test
	void refreshMaxRequestsBelowOneIsRejected() {
		assertThatThrownBy(() -> new RateLimitProperties(true,
						Duration.ofMinutes(1), 10,
						Duration.ofMinutes(1), 600,
						Duration.ofMinutes(1), 10,
						Duration.ofMinutes(1), 60,
						100000, Duration.ofMinutes(10),
						Duration.ofMinutes(1), 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("rate-limit.refresh-max-requests must be >= 1");
	}

	@Test
	void existingTierDefaultsAreUnchanged() {
		RateLimitProperties properties = defaults();

		assertThat(properties.authWindow()).isEqualTo(Duration.ofMinutes(1));
		assertThat(properties.authMaxRequests()).isEqualTo(10);
		assertThat(properties.apiMaxRequests()).isEqualTo(600);
		assertThat(properties.explanationMaxRequests()).isEqualTo(10);
		assertThat(properties.anonymousMaxRequests()).isEqualTo(60);
		assertThat(properties.maxTrackedKeys()).isEqualTo(100000);
		assertThat(properties.idleTtl()).isEqualTo(Duration.ofMinutes(10));
	}
}
