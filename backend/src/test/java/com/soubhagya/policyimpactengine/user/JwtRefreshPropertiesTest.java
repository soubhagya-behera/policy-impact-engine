package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockPropertySource;

/**
 * Phase 14-A/1 — binding and validation tests for the refresh-token
 * lifetime bound from {@code security.jwt.refresh-token-ttl} (see
 * DECISIONS.md ADR-029).
 *
 * <p>No Spring context, no database. Property binding runs through
 * Spring's {@link Binder} so defaults and custom values resolve
 * exactly as they do at startup; positivity is enforced fail-fast by
 * {@link JwtService}, matching the existing secret convention.
 */
class JwtRefreshPropertiesTest {

	private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
	private static final String SECRET = "test-secret-for-jwt-refresh-unit-tests-0000";

	private static JwtProperties bind(String... pairs) {
		MockPropertySource source = new MockPropertySource();
		for (int i = 0; i < pairs.length; i += 2) {
			source.setProperty(pairs[i], pairs[i + 1]);
		}
		Binder binder = new Binder(ConfigurationPropertySources.from(source));
		return binder.bind("security.jwt", JwtProperties.class)
				.orElseThrow(() -> new IllegalStateException("Binding must produce properties"));
	}

	private static JwtService service(JwtProperties properties) {
		return new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void defaultRefreshTtlBindsToThirtyDays() {
		JwtProperties properties = bind("security.jwt.secret", SECRET);

		assertThat(service(properties).refreshTokenExpiresInSeconds())
				.isEqualTo(Duration.ofHours(720).toSeconds());
	}

	@Test
	void customRefreshTtlBindsCorrectly() {
		JwtProperties properties = bind(
				"security.jwt.secret", SECRET,
				"security.jwt.refresh-token-ttl", "PT168H");

		assertThat(service(properties).refreshTokenExpiresInSeconds())
				.isEqualTo(Duration.ofHours(168).toSeconds());
	}

	@Test
	void zeroRefreshTtlIsRejected() {
		JwtProperties properties = bind(
				"security.jwt.secret", SECRET,
				"security.jwt.refresh-token-ttl", "PT0S");

		assertThatThrownBy(() -> service(properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("security.jwt.refresh-token-ttl must be positive");
	}

	@Test
	void negativeRefreshTtlIsRejected() {
		assertThatThrownBy(() -> service(
						new JwtProperties(SECRET, null, Duration.ofHours(-1))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("security.jwt.refresh-token-ttl must be positive");
	}

	@Test
	void accessTokenTtlBehaviorIsUnchanged() {
		JwtService defaults = service(bind("security.jwt.secret", SECRET));

		assertThat(defaults.accessTokenExpiresInSeconds()).isEqualTo(900L);
		assertThat(defaults.refreshTokenExpiresInSeconds())
				.isEqualTo(Duration.ofHours(720).toSeconds());

		JwtService customAccess = service(new JwtProperties(
				SECRET, Duration.ofMinutes(5), Duration.ofDays(7)));

		assertThat(customAccess.accessTokenExpiresInSeconds()).isEqualTo(300L);
		assertThat(customAccess.refreshTokenExpiresInSeconds())
				.isEqualTo(Duration.ofDays(7).toSeconds());
	}
}
