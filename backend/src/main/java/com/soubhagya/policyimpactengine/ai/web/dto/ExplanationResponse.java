package com.soubhagya.policyimpactengine.ai.web.dto;

import java.util.List;
import java.util.UUID;

import com.soubhagya.policyimpactengine.ai.FallbackReason;

/**
 * Phase 12 — explanation response: model or fallback prose plus the
 * authoritative deterministic facts it explains (see DECISIONS.md
 * ADR-024 §§7–8).
 *
 * <p>{@code aggregateScore}, {@code aggregateBand}, and {@code
 * actionKinds} are built from persisted application facts, never
 * parsed from model prose. {@code fallbackReason} is present if and
 * only if {@code fallback} is true.
 */
public record ExplanationResponse(

		UUID assessmentId,
		String explanation,
		int aggregateScore,
		String aggregateBand,
		List<String> actionKinds,
		String model,
		boolean fallback,
		FallbackReason fallbackReason

) {

	public ExplanationResponse {
		if (assessmentId == null) {
			throw new IllegalArgumentException("Assessment id must not be null");
		}
		if (explanation == null || explanation.isBlank()) {
			throw new IllegalArgumentException("Explanation must not be blank");
		}
		if (aggregateBand == null || aggregateBand.isBlank()) {
			throw new IllegalArgumentException("Aggregate band must not be blank");
		}
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("Model must not be blank");
		}
		actionKinds = actionKinds == null ? List.of() : List.copyOf(actionKinds);
		if (fallback && fallbackReason == null) {
			throw new IllegalArgumentException("Fallback reason must not be null for fallback");
		}
		if (!fallback && fallbackReason != null) {
			throw new IllegalArgumentException("Fallback reason must be null for model output");
		}
	}
}
