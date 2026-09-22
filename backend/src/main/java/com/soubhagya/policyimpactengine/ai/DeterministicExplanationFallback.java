package com.soubhagya.policyimpactengine.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 12 — pure deterministic explanation renderer (see DECISIONS.md
 * ADR-024 §10).
 *
 * <p>Spring-free. Renders stable prose from exactly the same
 * allowlisted facts the model would receive, so fallback output is
 * golden-testable and can invent nothing. Used whenever AI is
 * disabled, unreachable, too slow, malformed, empty, or over budget.
 */
public final class DeterministicExplanationFallback {

	/** Model label reported for fallback prose. */
	public static final String FALLBACK_MODEL = "deterministic-fallback";

	private DeterministicExplanationFallback() {
		// Pure functions; not instantiable.
	}

	/**
	 * Renders what changed, why it mattered to this user, and what
	 * was recommended — each sentence grounded in one fact group.
	 */
	public static String render(ExplanationRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("Request must not be null");
		}
		StringBuilder text = new StringBuilder();
		text.append("Assessment explanation for ").append(request.policyName())
				.append(" (version ").append(request.versionNumber())
				.append(", previously ").append(request.previousVersionNumber())
				.append("). ");
		text.append("Overall impact: ").append(request.aggregateBand())
				.append(" (").append(request.aggregateScore()).append(" out of 100). ");
		if (request.items().isEmpty()) {
			text.append("No privacy concepts were affected. ");
		}
		else {
			List<String> findings = new ArrayList<>(request.items().size());
			for (ExplanationRequest.FactItem item : request.items()) {
				findings.add(item.conceptLabel() + " (" + item.changeType()
						+ ", sensitivity " + item.effectiveSensitivity() + "/5, "
						+ item.personalizedNormalized() + "/100 "
						+ item.personalizedBand() + ")");
			}
			text.append("Affected areas: ").append(String.join("; ", findings)).append(". ");
		}
		List<String> actions = new ArrayList<>(request.recommendations().size());
		for (ExplanationRequest.RecommendationItem recommendation : request.recommendations()) {
			if (!"NONE_REQUIRED".equals(recommendation.actionKind())) {
				String action = recommendation.actionKind();
				if (recommendation.conceptCode() != null) {
					action += " for " + recommendation.conceptCode();
				}
				actions.add(action);
			}
		}
		if (actions.isEmpty()) {
			text.append("No action is required.");
		}
		else {
			text.append("Recommended actions: ").append(String.join("; ", actions)).append(".");
		}
		return text.toString();
	}
}
