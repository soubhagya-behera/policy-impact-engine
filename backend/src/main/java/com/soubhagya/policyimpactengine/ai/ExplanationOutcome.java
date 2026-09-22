package com.soubhagya.policyimpactengine.ai;

/**
 * Phase 12 — typed result of one explanation attempt (see DECISIONS.md
 * ADR-024 §§9–10).
 *
 * <p>Spring-free. Providers never throw transport or parsing
 * exceptions to the service; every failure arrives as a {@link
 * Failure} with a closed reason the service maps to deterministic
 * fallback. Providers never produce {@link FallbackReason#DISABLED}.
 */
public sealed interface ExplanationOutcome
		permits ExplanationOutcome.Success, ExplanationOutcome.Failure {

	/** Model prose plus the model that produced it. */
	record Success(String text, String model) implements ExplanationOutcome {
		public Success {
			if (text == null || text.isBlank()) {
				throw new IllegalArgumentException("Text must not be blank");
			}
			if (model == null || model.isBlank()) {
				throw new IllegalArgumentException("Model must not be blank");
			}
		}
	}

	/** Typed provider failure; never leaks transport details. */
	record Failure(FallbackReason reason) implements ExplanationOutcome {
		public Failure {
			if (reason == null) {
				throw new IllegalArgumentException("Reason must not be null");
			}
		}
	}
}
