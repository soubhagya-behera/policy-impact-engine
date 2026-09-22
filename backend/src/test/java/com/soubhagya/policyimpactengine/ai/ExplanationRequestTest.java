package com.soubhagya.policyimpactengine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Phase 12 — pure unit tests for the allowlisted explanation
 * request: construction validation, deterministic prompt rendering
 * with the exact grounding instruction, and the privacy boundary
 * (only allowlisted facts appear; forbidden data never does).
 */
class ExplanationRequestTest {

	private static final UUID ASSESSMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Test
	void promptTextRendersFactsInFixedOrderWithInstruction() {
		ExplanationRequest request = new ExplanationRequest(ASSESSMENT, "Acme Policy",
				2, 1, 80, "CRITICAL", 1,
				List.of(new ExplanationRequest.FactItem("Location", "MODIFIED", 5, 80,
						"HIGH", 1)),
				List.of(new ExplanationRequest.RecommendationItem("REVIEW_SETTINGS",
						"LOCATION", "HIGH")));

		String prompt = request.promptText();

		assertThat(prompt).startsWith("Explain the following persisted privacy-policy"
				+ " impact assessment.\n");
		assertThat(prompt).contains("Assessment: " + ASSESSMENT);
		assertThat(prompt).contains("Policy: Acme Policy, version 2 (previous 1)");
		assertThat(prompt).contains("Overall impact: 80/100 (CRITICAL)");
		assertThat(prompt).contains(
				"- Location (MODIFIED): sensitivity 5/5 -> 80/100 (HIGH)");
		assertThat(prompt).contains("- REVIEW_SETTINGS for LOCATION (HIGH)");
		assertThat(prompt).endsWith(
				"Explain only these facts. Restate numbers exactly. Invent nothing.");
	}

	@Test
	void emptyFindingsAndRecommendationsRenderExplicitly() {
		ExplanationRequest request = new ExplanationRequest(ASSESSMENT, "Acme Policy",
				1, 0, 0, "NONE", 1, null, null);

		assertThat(request.items()).isEmpty();
		assertThat(request.recommendations()).isEmpty();
		assertThat(request.promptText()).contains("Findings: none.\n");
		assertThat(request.promptText()).contains("Recommendations: none.\n");
	}

	@Test
	void nullConceptCodeRendersAssessmentWide() {
		ExplanationRequest request = new ExplanationRequest(ASSESSMENT, "Acme Policy",
				1, 0, 0, "NONE", 1, List.of(),
				List.of(new ExplanationRequest.RecommendationItem("NONE_REQUIRED", null,
						"NONE")));

		assertThat(request.promptText()).contains("- NONE_REQUIRED (NONE)");
	}

	@Test
	void promptContainsOnlyAllowlistedFacts() {
		ExplanationRequest request = new ExplanationRequest(ASSESSMENT, "Acme Policy",
				2, 1, 55, "MEDIUM", 1,
				List.of(new ExplanationRequest.FactItem("Cookies", "ADDED", 2, 30,
						"LOW", 1)),
				List.of(new ExplanationRequest.RecommendationItem("OPT_OUT_SHARING",
						"COOKIES", "MEDIUM")));

		String lower = request.promptText().toLowerCase();

		assertThat(lower).doesNotContain("http");
		assertThat(lower).doesNotContain("password");
		assertThat(lower).doesNotContain("token");
		assertThat(lower).doesNotContain("secret");
		assertThat(lower).doesNotContain("email");
		assertThat(lower).doesNotContain("authorization");
		assertThat(lower).doesNotContain("@");
		assertThat(lower).doesNotContain("evidence");
		assertThat(lower).doesNotContain("fragment");
	}

	@Test
	void promptRenderingIsDeterministic() {
		ExplanationRequest first = sample();
		ExplanationRequest second = sample();

		assertThat(first.promptText()).isEqualTo(second.promptText());
		assertThat(first).isEqualTo(second);
		assertThat(first.hashCode()).isEqualTo(second.hashCode());
	}

	@Test
	void invalidRequestsAreRejected() {
		assertThatThrownBy(() -> new ExplanationRequest(null, "Acme Policy", 2, 1, 80,
				"CRITICAL", 1, List.of(), List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ExplanationRequest(ASSESSMENT, "  ", 2, 1, 80,
				"CRITICAL", 1, List.of(), List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ExplanationRequest(ASSESSMENT, "Acme Policy", 2, 1,
				80, null, 1, List.of(), List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ExplanationRequest.FactItem(null, "MODIFIED", 5,
				80, "HIGH", 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ExplanationRequest.RecommendationItem("  ", "LOCATION",
				"HIGH")).isInstanceOf(IllegalArgumentException.class);
	}

	private static ExplanationRequest sample() {
		return new ExplanationRequest(ASSESSMENT, "Acme Policy", 2, 1, 80, "CRITICAL",
				1,
				List.of(new ExplanationRequest.FactItem("Location", "MODIFIED", 5, 80,
						"HIGH", 1)),
				List.of(new ExplanationRequest.RecommendationItem("REVIEW_SETTINGS",
						"LOCATION", "HIGH")));
	}
}
