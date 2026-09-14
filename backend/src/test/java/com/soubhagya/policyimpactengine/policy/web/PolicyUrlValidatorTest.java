package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PolicyUrlValidatorTest {

	@Test
	void acceptsPlainHttpsUrl() {
		assertThat(PolicyUrlValidator.validate("https://example.com/privacy"))
				.isEqualTo("https://example.com/privacy");
	}

	@Test
	void acceptsHttpsUrlWithPathAndQuery() {
		assertThat(PolicyUrlValidator.validate("https://example.com/policies/privacy?lang=en&v=2"))
				.isEqualTo("https://example.com/policies/privacy?lang=en&v=2");
	}

	@Test
	void trimsSurroundingWhitespace() {
		assertThat(PolicyUrlValidator.validate("  https://example.com/privacy  "))
				.isEqualTo("https://example.com/privacy");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://example.com/privacy",
			"ftp://example.com/privacy",
			"file:///etc/passwd",
			"javascript:alert(1)",
			"data:text/plain,hello"
	})
	void rejectsUnsupportedSchemes(String url) {
		assertThatThrownBy(() -> PolicyUrlValidator.validate(url))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"example.com/privacy",
			"/relative/path",
			"https:///privacy",
			"https://",
			"https://exa mple.com/privacy",
			"https://user@example.com/privacy",
			"not a url",
			"   "
	})
	void rejectsMalformedOrHostlessUrls(String url) {
		assertThatThrownBy(() -> PolicyUrlValidator.validate(url))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNullUrl() {
		assertThatThrownBy(() -> PolicyUrlValidator.validate(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("blank");
	}

	@Test
	void rejectsOverlongUrl() {
		String url = "https://example.com/" + "a".repeat(2048);

		assertThatThrownBy(() -> PolicyUrlValidator.validate(url))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("2048");
	}

	@Test
	void rejectionMessageNamesHttpsRequirement() {
		assertThatThrownBy(() -> PolicyUrlValidator.validate("http://example.com/privacy"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("https");
	}

}
