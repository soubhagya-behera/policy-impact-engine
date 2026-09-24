package com.soubhagya.policyimpactengine.common.web.cors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Phase 13-D — unit tests for {@link CorsProperties} (see DECISIONS.md
 * ADR-027). Deny-by-default shape, locked method/header subsets, exact
 * origin rules, credentials locked to false, and fail-fast validation.
 * No Spring context, no database.
 */
class CorsPropertiesTest {

	@Test
	void nullsResolveToDenyByDefaultShape() {
		CorsProperties properties =
				new CorsProperties(null, null, null, null, null);

		assertThat(properties.allowedOrigins()).isEmpty();
		assertThat(properties.allowedMethods())
				.containsExactly("GET", "POST", "PUT");
		assertThat(properties.allowedHeaders())
				.containsExactly("Authorization", "Content-Type");
		assertThat(properties.allowCredentials()).isFalse();
		assertThat(properties.maxAge()).isEqualTo(Duration.ofHours(1));
	}

	@Test
	void singleExactOriginIsAccepted() {
		CorsProperties properties = new CorsProperties(
				List.of("https://app.example.com"), null, null, false, null);

		assertThat(properties.allowedOrigins())
				.containsExactly("https://app.example.com");
	}

	@Test
	void httpOriginWithPortIsAccepted() {
		CorsProperties properties = new CorsProperties(
				List.of("http://localhost:3000"), null, null, null, null);

		assertThat(properties.allowedOrigins())
				.containsExactly("http://localhost:3000");
	}

	@Test
	void wildcardOriginIsRejected() {
		assertThatThrownBy(() -> new CorsProperties(
						List.of("https://*.example.com"), null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void originWithPathIsRejected() {
		assertThatThrownBy(() -> new CorsProperties(
						List.of("https://app.example.com/ui"), null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nonHttpOriginIsRejected() {
		assertThatThrownBy(() -> new CorsProperties(
						List.of("ftp://files.example.com"), null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void blankOriginIsRejected() {
		assertThatThrownBy(() -> new CorsProperties(
						List.of("  "), null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void methodOutsideLockdownIsRejected() {
		assertThatThrownBy(() -> new CorsProperties(
						null, List.of("GET", "DELETE"), null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void headerOutsideLockdownIsRejected() {
		assertThatThrownBy(() -> new CorsProperties(
						null, null, List.of("X-Custom"), null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void credentialsTrueIsRejected() {
		assertThatThrownBy(() -> new CorsProperties(
						null, null, null, true, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nonPositiveMaxAgeIsRejected() {
		assertThatThrownBy(() -> new CorsProperties(
						null, null, null, null, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
