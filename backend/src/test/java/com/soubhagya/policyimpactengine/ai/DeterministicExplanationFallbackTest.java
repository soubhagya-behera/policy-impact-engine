package com.soubhagya.policyimpactengine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Phase 12 — golden tests for the deterministic fallback renderer:
 * stable prose grounded in exactly the supplied facts, covering
 * no-impact, single-finding, and multi-concept shapes.
 */
class DeterministicExplanationFallbackTest {

	private static final UUID ASSESSMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Test
	void noImpactGolden() {
		ExplanationRequest request = new ExplanationRequest(ASSESSMENT, "Acme Policy",
				1, 0, 0, "NONE", 1, List.of(),
				List.of(new ExplanationRequest.RecommendationItem("NONE_REQUIRED", null,
						"NONE")));

		assertThat(DeterministicExplanationFallback.render(request)).isEqualTo(
				"Assessment explanation for Acme Policy (version 1, previously 0). "
						+ "Overall impact: NONE (0 out of 100). "
						+ "No privacy concepts were affected. "
						+ "No action is required.");
	}

	@Test
	void singleFindingGolden() {
		ExplanationRequest request = new ExplanationRequest(ASSESSMENT, "Acme Policy",
				2, 1, 30, "LOW", 1,
				List.of(new ExplanationRequest.FactItem("Cookies", "ADDED", 2, 30,
						"LOW", 1)),
				List.of(new ExplanationRequest.RecommendationItem("NONE_REQUIRED", null,
						"NONE")));

		assertThat(DeterministicExplanationFallback.render(request)).isEqualTo(
				"Assessment explanation for Acme Policy (version 2, previously 1). "
						+ "Overall impact: LOW (30 out of 100). "
						+ "Affected areas: Cookies (ADDED, sensitivity 2/5, 30/100 LOW). "
						+ "No action is required.");
	}

	@Test
	void multiConceptHighImpactGolden() {
		ExplanationRequest request = new ExplanationRequest(ASSESSMENT, "Acme Policy",
				3, 2, 85, "CRITICAL", 1,
				List.of(
						new ExplanationRequest.FactItem("Location", "MODIFIED", 5, 80,
								"HIGH", 1),
						new ExplanationRequest.FactItem("Third-party sharing", "ADDED", 4,
								70, "MEDIUM", 1)),
				List.of(
						new ExplanationRequest.RecommendationItem("REVIEW_SETTINGS",
								"LOCATION", "HIGH"),
						new ExplanationRequest.RecommendationItem("OPT_OUT_SHARING",
								"THIRD_PARTY_SHARING", "MEDIUM")));

		assertThat(DeterministicExplanationFallback.render(request)).isEqualTo(
				"Assessment explanation for Acme Policy (version 3, previously 2). "
						+ "Overall impact: CRITICAL (85 out of 100). "
						+ "Affected areas: Location (MODIFIED, sensitivity 5/5, 80/100 HIGH); "
						+ "Third-party sharing (ADDED, sensitivity 4/5, 70/100 MEDIUM). "
						+ "Recommended actions: REVIEW_SETTINGS for LOCATION; "
						+ "OPT_OUT_SHARING for THIRD_PARTY_SHARING.");
	}

	@Test
	void renderingIsStableAndInventsNothing() {
		ExplanationRequest request = new ExplanationRequest(ASSESSMENT, "Acme Policy",
				2, 1, 55, "MEDIUM", 1,
				List.of(new ExplanationRequest.FactItem("Cookies", "ADDED", 2, 30,
						"LOW", 1)),
				List.of(new ExplanationRequest.RecommendationItem("OPT_OUT_SHARING",
						"COOKIES", "MEDIUM")));

		String first = DeterministicExplanationFallback.render(request);
		String second = DeterministicExplanationFallback.render(request);

		assertThat(first).isEqualTo(second);
		assertThat(first.toLowerCase()).doesNotContain("http");
		assertThat(first.toLowerCase()).doesNotContain("token");
		assertThat(first.toLowerCase()).doesNotContain("password");
		assertThatThrownBy(() -> DeterministicExplanationFallback.render(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
