package com.soubhagya.policyimpactengine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soubhagya.policyimpactengine.ai.web.dto.ExplanationResponse;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentBreakdownResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentDetailResponse;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyResponse;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.web.dto.RecommendationSummaryResponse;

/**
 * Phase 12 — unit tests for explanation orchestration with a stub
 * provider: authoritative passthrough, every failure mapped to the
 * right fallback reason, disabled/over-budget short-circuits that
 * never invoke the provider, and ownership errors that propagate
 * instead of degrading.
 */
@ExtendWith(MockitoExtension.class)
class AiExplanationServiceTest {

	private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID ASSESSMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID POLICY = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock private ImpactAssessmentService assessmentService;
	@Mock private RecommendationService recommendationService;
	@Mock private PolicyService policyService;
	@Mock private PrivacyConceptRepository conceptRepository;
	@Mock private AiExplanationProvider provider;

	private AiProperties enabled;
	private AiProperties disabled;

	@BeforeEach
	void properties() {
		enabled = new AiProperties(true, "ollama", "http://localhost:11434", "stub-model",
				Duration.ofSeconds(2), Duration.ofSeconds(30), 4000, 300);
		disabled = new AiProperties(false, "ollama", "http://localhost:11434", "stub-model",
				Duration.ofSeconds(2), Duration.ofSeconds(30), 4000, 300);
	}

	@Test
	void successReturnsModelProseWithAuthoritativeFacts() {
		stubReads();
		AiExplanationService service = service(enabled);
		when(provider.explain(any()))
				.thenReturn(new ExplanationOutcome.Success("Lies: score 0 NONE.", "stub-model"));

		ExplanationResponse response = service.explain(USER, ASSESSMENT);

		assertThat(response.assessmentId()).isEqualTo(ASSESSMENT);
		assertThat(response.explanation()).isEqualTo("Lies: score 0 NONE.");
		assertThat(response.aggregateScore()).isEqualTo(80);
		assertThat(response.aggregateBand()).isEqualTo("CRITICAL");
		assertThat(response.actionKinds()).containsExactly("REVIEW_SETTINGS");
		assertThat(response.model()).isEqualTo("stub-model");
		assertThat(response.fallback()).isFalse();
		assertThat(response.fallbackReason()).isNull();
		verify(provider).explain(any());
	}

	@Test
	void everyProviderFailureMapsToMatchingFallback() {
		FallbackReason[] reasons = { FallbackReason.UNAVAILABLE, FallbackReason.TIMEOUT,
				FallbackReason.MALFORMED, FallbackReason.EMPTY, FallbackReason.OVER_BUDGET };
		for (FallbackReason reason : reasons) {
			clearInvocations(provider);
			stubReads();
			AiExplanationService service = service(enabled);
			when(provider.explain(any())).thenReturn(new ExplanationOutcome.Failure(reason));

			ExplanationResponse response = service.explain(USER, ASSESSMENT);

			assertThat(response.fallback()).isTrue();
			assertThat(response.fallbackReason()).isEqualTo(reason);
			assertThat(response.model())
					.isEqualTo(DeterministicExplanationFallback.FALLBACK_MODEL);
			assertThat(response.aggregateScore()).isEqualTo(80);
			assertThat(response.aggregateBand()).isEqualTo("CRITICAL");
			assertThat(response.actionKinds()).containsExactly("REVIEW_SETTINGS");
			assertThat(response.explanation()).isEqualTo(
					DeterministicExplanationFallback.render(capturedRequest()));
		}
	}

	@Test
	void disabledAiFallsBackWithoutInvokingProvider() {
		stubReads();

		ExplanationResponse response = service(disabled).explain(USER, ASSESSMENT);

		assertThat(response.fallback()).isTrue();
		assertThat(response.fallbackReason()).isEqualTo(FallbackReason.DISABLED);
		verifyNoInteractions(provider);
	}

	@Test
	void overBudgetShortCircuitsWithoutInvokingProvider() {
		stubReads();
		AiProperties tiny = new AiProperties(true, "ollama", "http://localhost:11434",
				"stub-model", Duration.ofSeconds(2), Duration.ofSeconds(30), 10, 300);

		ExplanationResponse response = service(tiny).explain(USER, ASSESSMENT);

		assertThat(response.fallback()).isTrue();
		assertThat(response.fallbackReason()).isEqualTo(FallbackReason.OVER_BUDGET);
		verify(provider, never()).explain(any());
	}

	@Test
	void foreignAssessmentPropagatesInsteadOfFallback() {
		when(assessmentService.getAssessmentDetail(USER, ASSESSMENT))
				.thenThrow(new NoSuchElementException("Assessment not found"));

		assertThatThrownBy(() -> service(enabled).explain(USER, ASSESSMENT))
				.isInstanceOf(NoSuchElementException.class);
		verifyNoInteractions(provider);
	}

	@Test
	void nullIdsAreRejected() {
		assertThatThrownBy(() -> service(enabled).explain(null, ASSESSMENT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service(enabled).explain(USER, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void providerReceivesOnlyAllowlistedFacts() {
		stubReads();
		when(provider.explain(any()))
				.thenReturn(new ExplanationOutcome.Success("Prose.", "stub-model"));
		service(enabled).explain(USER, ASSESSMENT);

		String prompt = capturedRequest().promptText().toLowerCase();
		assertThat(prompt).doesNotContain("http");
		assertThat(prompt).doesNotContain("password");
		assertThat(prompt).doesNotContain("token");
		assertThat(prompt).doesNotContain("secret");
		assertThat(prompt).doesNotContain("email");
		assertThat(prompt).doesNotContain("evidence");
		assertThat(prompt).doesNotContain("fragment");
		assertThat(prompt).contains("location");
	}

	private AiExplanationService service(AiProperties properties) {
		return new AiExplanationService(assessmentService, recommendationService,
				policyService, conceptRepository, provider, properties);
	}

	private void stubReads() {
		Instant at = Instant.parse("2026-09-18T10:00:00Z");
		when(assessmentService.getAssessmentDetail(USER, ASSESSMENT))
				.thenReturn(new ImpactAssessmentDetailResponse(ASSESSMENT, POLICY, 2, 1,
						80, "CRITICAL", 1, at,
						List.of(new ImpactAssessmentBreakdownResponse(UUID.randomUUID(),
								"LOCATION", "MODIFIED", 60, "MEDIUM", 1, 5, 80, "HIGH", 1))));
		when(recommendationService.listRecommendationResponses(USER)).thenReturn(List.of(
				new RecommendationSummaryResponse(UUID.randomUUID(), ASSESSMENT, "R1", 1,
						"REVIEW_SETTINGS", "LOCATION", 80, "HIGH", 1, at)));
		when(policyService.get(USER, POLICY)).thenReturn(
				new PolicyResponse(POLICY, "Acme Policy", "https://example.com/privacy",
						"ACTIVE", at, at));
		PrivacyConcept concept = mock(PrivacyConcept.class);
		when(concept.getLabel()).thenReturn("Location");
		when(conceptRepository.findByCode("LOCATION")).thenReturn(Optional.of(concept));
	}

	private ExplanationRequest capturedRequest() {
		ArgumentCaptor<ExplanationRequest> captor =
				ArgumentCaptor.forClass(ExplanationRequest.class);
		verify(provider).explain(captor.capture());
		return captor.getValue();
	}
}
