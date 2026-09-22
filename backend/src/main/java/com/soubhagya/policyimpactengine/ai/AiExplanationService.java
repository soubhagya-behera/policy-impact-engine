package com.soubhagya.policyimpactengine.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.soubhagya.policyimpactengine.ai.web.dto.ExplanationResponse;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentDetailResponse;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.web.dto.RecommendationSummaryResponse;

/**
 * Phase 12 — user-triggered explanation orchestration (see DECISIONS.md
 * ADR-024).
 *
 * <p>Reads one already-persisted assessment and its recommendations
 * through the existing user-scoped paths (a foreign assessment id
 * behaves as not-found), builds the allowlisted {@link
 * ExplanationRequest}, enforces the input budget, and invokes the
 * provider only when AI is enabled. Authoritative response fields
 * come from persisted facts, never from model prose.
 *
 * <p>Deliberately not transactional: the model call must never run
 * inside a database transaction. Reads delegate to the existing
 * short read-only service transactions; nothing here writes,
 * persists, caches, or audits.
 */
@Service
public class AiExplanationService {

	private final ImpactAssessmentService assessmentService;
	private final RecommendationService recommendationService;
	private final PolicyService policyService;
	private final PrivacyConceptRepository conceptRepository;
	private final AiExplanationProvider provider;
	private final AiProperties properties;

	public AiExplanationService(ImpactAssessmentService assessmentService,
			RecommendationService recommendationService, PolicyService policyService,
			PrivacyConceptRepository conceptRepository, AiExplanationProvider provider,
			AiProperties properties) {
		if (assessmentService == null || recommendationService == null || policyService == null
				|| conceptRepository == null || provider == null || properties == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.assessmentService = assessmentService;
		this.recommendationService = recommendationService;
		this.policyService = policyService;
		this.conceptRepository = conceptRepository;
		this.provider = provider;
		this.properties = properties;
	}

	/**
	 * Explains the user's persisted assessment, falling back to
	 * deterministic prose whenever AI is disabled, over budget, or
	 * the provider fails. Ownership, authentication, and domain
	 * errors propagate unchanged — never as fallback.
	 */
	public ExplanationResponse explain(UUID userId, UUID assessmentId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (assessmentId == null) {
			throw new IllegalArgumentException("Assessment id must not be null");
		}
		ImpactAssessmentDetailResponse detail =
				assessmentService.getAssessmentDetail(userId, assessmentId);
		List<RecommendationSummaryResponse> recommendations = recommendationService
				.listRecommendationResponses(userId).stream()
				.filter(row -> row.assessmentId().equals(assessmentId))
				.toList();
		ExplanationRequest request = buildRequest(detail, recommendations,
				policyService.get(userId, detail.policyId()).name());
		if (!properties.enabled()) {
			return fallback(request, detail, recommendations, FallbackReason.DISABLED);
		}
		if (request.promptText().length() > properties.maxInputChars()) {
			return fallback(request, detail, recommendations, FallbackReason.OVER_BUDGET);
		}
		ExplanationOutcome outcome = provider.explain(request);
		if (outcome instanceof ExplanationOutcome.Success success) {
			return new ExplanationResponse(detail.id(), success.text(),
					detail.aggregateScore(), detail.aggregateBand(),
					actionKinds(recommendations), success.model(), false, null);
		}
		ExplanationOutcome.Failure failure = (ExplanationOutcome.Failure) outcome;
		return fallback(request, detail, recommendations, failure.reason());
	}

	private ExplanationRequest buildRequest(ImpactAssessmentDetailResponse detail,
			List<RecommendationSummaryResponse> recommendations, String policyName) {
		List<ExplanationRequest.FactItem> items = new ArrayList<>(detail.breakdowns().size());
		for (var breakdown : detail.breakdowns()) {
			String label = conceptRepository.findByCode(breakdown.conceptCode())
					.map(concept -> concept.getLabel())
					.orElse(breakdown.conceptCode());
			items.add(new ExplanationRequest.FactItem(label, breakdown.changeType(),
					breakdown.effectiveSensitivity(), breakdown.personalizedNormalized(),
					breakdown.personalizedBand(), breakdown.systemRulesVersion()));
		}
		List<ExplanationRequest.RecommendationItem> recItems =
				new ArrayList<>(recommendations.size());
		for (RecommendationSummaryResponse recommendation : recommendations) {
			recItems.add(new ExplanationRequest.RecommendationItem(
					recommendation.actionKind(), recommendation.conceptCode(),
					recommendation.personalizedBand()));
		}
		return new ExplanationRequest(detail.id(), policyName, detail.versionNumber(),
				detail.previousVersionNumber(), detail.aggregateScore(), detail.aggregateBand(),
				detail.personalizationRulesVersion(), items, recItems);
	}

	private ExplanationResponse fallback(ExplanationRequest request,
			ImpactAssessmentDetailResponse detail,
			List<RecommendationSummaryResponse> recommendations, FallbackReason reason) {
		return new ExplanationResponse(detail.id(),
				DeterministicExplanationFallback.render(request), detail.aggregateScore(),
				detail.aggregateBand(), actionKinds(recommendations),
				DeterministicExplanationFallback.FALLBACK_MODEL, true, reason);
	}

	private static List<String> actionKinds(
			List<RecommendationSummaryResponse> recommendations) {
		List<String> actionKinds = new ArrayList<>(recommendations.size());
		for (RecommendationSummaryResponse recommendation : recommendations) {
			actionKinds.add(recommendation.actionKind());
		}
		return actionKinds;
	}
}
