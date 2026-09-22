package com.soubhagya.policyimpactengine.ai;

/**
 * Phase 12 — explanation provider abstraction (see DECISIONS.md
 * ADR-024 §9).
 *
 * <p>Spring-free. Takes an allowlisted {@link ExplanationRequest}
 * and returns prose or a typed failure; never throws provider or
 * network exceptions. The only production implementation in Phase 12
 * targets the local Ollama runtime; the deterministic fallback is
 * selected by the service, not by implementations of this interface.
 */
public interface AiExplanationProvider {

	/**
	 * Produces explanation prose for already-persisted facts, or a
	 * typed failure. Must not make network calls the caller cannot
	 * bound, and must not retain prompts or responses.
	 */
	ExplanationOutcome explain(ExplanationRequest request);
}
