package com.soubhagya.policyimpactengine.policy.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CreatePolicyRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void acceptsValidRequest() {
		var request = new CreatePolicyRequest("Acme Privacy Policy", "https://example.com/privacy");

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void rejectsBlankName() {
		var request = new CreatePolicyRequest("   ", "https://example.com/privacy");

		assertThat(validator.validate(request))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("name"));
	}

	@Test
	void rejectsBlankUrl() {
		var request = new CreatePolicyRequest("Acme Privacy Policy", "   ");

		assertThat(validator.validate(request))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("url"));
	}

	@Test
	void rejectsOverlongNameAndUrl() {
		var request = new CreatePolicyRequest("n".repeat(256), "https://example.com/" + "a".repeat(2048));

		assertThat(validator.validate(request))
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsExactlyInAnyOrder("name", "url");
	}

}
