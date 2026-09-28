package com.soubhagya.policyimpactengine.impact.web;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.impact.ImpactSummaryReadService;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactSummaryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUsers;

/**
 * Phase 14-B/4 — authenticated impact-summary read API. Thin:
 * resolves the user id exclusively from the authenticated principal
 * before any business read, delegates to the application service,
 * and returns the single summary object.
 *
 * <p>Identity comes only from the principal — never from query
 * parameters, bodies, path variables, or headers. The response is a
 * single JSON object with no pagination. Nothing is created,
 * recomputed, or mutated here.
 */
@RestController
@RequestMapping("/api/v1/me/impact-summary")
public class ImpactSummaryController {

	private final ImpactSummaryReadService service;

	public ImpactSummaryController(ImpactSummaryReadService service) {
		if (service == null) {
			throw new IllegalArgumentException("ImpactSummaryReadService must not be null");
		}
		this.service = service;
	}

	@GetMapping
	public ImpactSummaryResponse getSummary(Authentication authentication) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.getSummary(userId);
	}

}
