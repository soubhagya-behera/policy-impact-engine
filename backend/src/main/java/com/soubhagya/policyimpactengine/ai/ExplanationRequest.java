package com.soubhagya.policyimpactengine.ai;

import java.util.List;
import java.util.UUID;

/**
 * Phase 12 — the complete, allowlisted fact set an explanation may
 * be built from (see DECISIONS.md ADR-024 §§5–6).
 *
 * <p>Plain Java, Spring-free: only persisted deterministic facts,
 * never raw policy content, diffs, evidence, URLs, credentials, or
 * other users' data. {@link #promptText()} is the single-sourced
 * rendering consumed by both the provider and the input-budget
 * check, so the budgeted text and the sent text cannot diverge.
 */
public record ExplanationRequest(

		UUID assessmentId,
		String policyName,
		int versionNumber,
		int previousVersionNumber,
		int aggregateScore,
		String aggregateBand,
		int personalizationRulesVersion,
		List<FactItem> items,
		List<RecommendationItem> recommendations

) {

	/** One breakdown row reduced to explainable facts. */
	public record FactItem(
			String conceptLabel,
			String changeType,
			int effectiveSensitivity,
			int personalizedNormalized,
			String personalizedBand,
			int systemRulesVersion) {
		public FactItem {
			if (conceptLabel == null || conceptLabel.isBlank()) {
				throw new IllegalArgumentException("Concept label must not be blank");
			}
			if (changeType == null || changeType.isBlank()) {
				throw new IllegalArgumentException("Change type must not be blank");
			}
			if (personalizedBand == null || personalizedBand.isBlank()) {
				throw new IllegalArgumentException("Personalized band must not be blank");
			}
		}
	}

	/** One persisted recommendation reduced to explainable facts. */
	public record RecommendationItem(
			String actionKind,
			String conceptCode,
			String personalizedBand) {
		public RecommendationItem {
			if (actionKind == null || actionKind.isBlank()) {
				throw new IllegalArgumentException("Action kind must not be blank");
			}
			if (personalizedBand == null || personalizedBand.isBlank()) {
				throw new IllegalArgumentException("Personalized band must not be blank");
			}
		}
	}

	public ExplanationRequest {
		if (assessmentId == null) {
			throw new IllegalArgumentException("Assessment id must not be null");
		}
		if (policyName == null || policyName.isBlank()) {
			throw new IllegalArgumentException("Policy name must not be blank");
		}
		if (aggregateBand == null || aggregateBand.isBlank()) {
			throw new IllegalArgumentException("Aggregate band must not be blank");
		}
		items = items == null ? List.of() : List.copyOf(items);
		recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
	}

	/**
	 * Renders the user content of the model prompt deterministically:
	 * the grounding instruction plus every allowlisted fact, in fixed
	 * order. Ends with the exact required instruction sentence.
	 */
	public String promptText() {
		StringBuilder prompt = new StringBuilder();
		prompt.append("Explain the following persisted privacy-policy impact assessment.\n");
		prompt.append("Assessment: ").append(assessmentId).append('\n');
		prompt.append("Policy: ").append(policyName)
				.append(", version ").append(versionNumber)
				.append(" (previous ").append(previousVersionNumber).append(")\n");
		prompt.append("Overall impact: ").append(aggregateScore).append("/100 (")
				.append(aggregateBand).append("); personalization rules v")
				.append(personalizationRulesVersion).append('\n');
		if (items.isEmpty()) {
			prompt.append("Findings: none.\n");
		}
		else {
			prompt.append("Findings:\n");
			for (FactItem item : items) {
				prompt.append("- ").append(item.conceptLabel())
						.append(" (").append(item.changeType()).append("): sensitivity ")
						.append(item.effectiveSensitivity()).append("/5 -> ")
						.append(item.personalizedNormalized()).append("/100 (")
						.append(item.personalizedBand()).append(")\n");
			}
		}
		if (recommendations.isEmpty()) {
			prompt.append("Recommendations: none.\n");
		}
		else {
			prompt.append("Recommendations:\n");
			for (RecommendationItem recommendation : recommendations) {
				prompt.append("- ").append(recommendation.actionKind());
				if (recommendation.conceptCode() != null) {
					prompt.append(" for ").append(recommendation.conceptCode());
				}
				prompt.append(" (").append(recommendation.personalizedBand()).append(")\n");
			}
		}
		prompt.append("Explain only these facts. Restate numbers exactly. Invent nothing.");
		return prompt.toString();
	}
}
