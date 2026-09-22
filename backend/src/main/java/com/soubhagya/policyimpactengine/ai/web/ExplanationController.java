package com.soubhagya.policyimpactengine.ai.web;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.ai.AiExplanationService;
import com.soubhagya.policyimpactengine.ai.web.dto.ExplanationResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUsers;

/**
 * Phase 12 — user-triggered assessment explanation (see DECISIONS.md
 * ADR-024 §§2–3).
 *
 * <p>Thin: resolves the user id exclusively from the authenticated
 * principal, delegates to the application service, and returns the
 * DTO. Identity never comes from the request body, query parameters,
 * headers, or path variables beyond the assessment resource
 * identifier. Cross-user access behaves as not-found. A POST
 * endpoint because model inference is costly and non-deterministic;
 * nothing is created, persisted, or audited by the call itself.
 */
@RestController
@RequestMapping("/api/v1/me/impact-assessments")
public class ExplanationController {

	private final AiExplanationService service;

	public ExplanationController(AiExplanationService service) {
		if (service == null) {
			throw new IllegalArgumentException("AiExplanationService must not be null");
		}
		this.service = service;
	}

	@PostMapping("/{assessmentId}/explanation")
	public ExplanationResponse explain(Authentication authentication,
			@PathVariable UUID assessmentId) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.explain(userId, assessmentId);
	}

}
