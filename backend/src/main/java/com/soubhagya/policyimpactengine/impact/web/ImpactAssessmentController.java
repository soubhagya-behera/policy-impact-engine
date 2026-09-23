package com.soubhagya.policyimpactengine.impact.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentDetailResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentSummaryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUsers;

/**
 * Authenticated impact-assessment read API. Thin: resolves the user
 * id exclusively from the authenticated principal, delegates to the
 * application service, and returns DTOs.
 *
 * <p>Identity comes only from
 * {@link AuthenticatedUsers#requireUserId(Authentication)} — never from
 * query parameters, bodies, path variables, or headers.
 * {@code assessmentId} is only the assessment resource identifier.
 * Cross-user access behaves as not-found and never reveals whether the
 * row exists. No scores are recomputed here; the service reads
 * persisted values.
 */
@RestController
@RequestMapping("/api/v1/me/impact-assessments")
public class ImpactAssessmentController {

	private final ImpactAssessmentService service;

	public ImpactAssessmentController(ImpactAssessmentService service) {
		if (service == null) {
			throw new IllegalArgumentException("ImpactAssessmentService must not be null");
		}
		this.service = service;
	}

	/**
	 * Phase 13-B — paginated assessment summaries (ADR-026): bare JSON
	 * array, newest first, windowed by {@code page}/{@code size}
	 * (defaults 0/20, maximum 100). The principal is resolved before
	 * pagination is validated so unauthenticated callers stay 401.
	 */
	@GetMapping
	public List<ImpactAssessmentSummaryResponse> list(Authentication authentication,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		FeedPagination pagination = FeedPagination.of(page, size);
		return service.listAssessmentSummariesPaged(userId, pagination.page(), pagination.size());
	}

	@GetMapping("/{assessmentId}")
	public ImpactAssessmentDetailResponse getById(Authentication authentication,
			@PathVariable UUID assessmentId) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.getAssessmentDetail(userId, assessmentId);
	}

}
