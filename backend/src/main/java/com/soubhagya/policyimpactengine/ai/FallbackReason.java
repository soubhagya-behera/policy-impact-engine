package com.soubhagya.policyimpactengine.ai;

/**
 * Phase 12 — closed vocabulary for why an explanation fell back to
 * deterministic prose (see DECISIONS.md ADR-024 §§10–11).
 *
 * <p>{@code DISABLED} is produced only by the service when AI is
 * switched off; the provider never emits it. Authentication,
 * ownership, and domain errors are never converted into these
 * reasons — they propagate as ordinary HTTP errors instead.
 */
public enum FallbackReason {
	DISABLED,
	UNAVAILABLE,
	TIMEOUT,
	MALFORMED,
	EMPTY,
	OVER_BUDGET
}
