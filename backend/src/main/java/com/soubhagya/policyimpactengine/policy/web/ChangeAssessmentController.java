package com.soubhagya.policyimpactengine.policy.web;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.policy.application.ChangeAssessmentReadService;
import com.soubhagya.policyimpactengine.policy.web.dto.ChangeAssessmentResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUsers;

/**
 * Phase 14-B/3 — authenticated change-assessment read API. Thin:
 * resolves the user id exclusively from the authenticated principal,
 * delegates to the application service, and returns the DTO.
 *
 * <p>Identity comes only from the principal — never from query
 * parameters, bodies, path variables, or headers.
 * {@code changeId} is only the change resource identifier. A foreign
 * or unknown change, or an owned change with no assessment, behaves
 * as not-found. Nothing is created, recomputed, or mutated here.
 */
@RestController
@RequestMapping("/api/v1/changes")
public class ChangeAssessmentController {

	private final ChangeAssessmentReadService service;

	public ChangeAssessmentController(ChangeAssessmentReadService service) {
		if (service == null) {
			throw new IllegalArgumentException("ChangeAssessmentReadService must not be null");
		}
		this.service = service;
	}

	@GetMapping("/{changeId}/assessment")
	public ChangeAssessmentResponse getAssessment(Authentication authentication,
			@PathVariable UUID changeId) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.getForChange(userId, changeId);
	}

}
