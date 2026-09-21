package com.soubhagya.policyimpactengine.recommendation.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.web.dto.RecommendationDetailResponse;
import com.soubhagya.policyimpactengine.recommendation.web.dto.RecommendationSummaryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUsers;

/**
 * Authenticated recommendation read API. Thin: resolves the user id
 * exclusively from the authenticated principal, delegates to the
 * application service, and returns DTOs.
 *
 * <p>Identity comes only from
 * {@link AuthenticatedUsers#requireUserId(Authentication)} — never from
 * query parameters, bodies, path variables, or headers.
 * {@code recommendationId} is only the recommendation resource
 * identifier. Ownership is derived through the assessment
 * (Recommendation → ImpactAssessment → User); cross-user access
 * behaves as not-found and never reveals whether the row exists.
 */
@RestController
@RequestMapping("/api/v1/me/recommendations")
public class RecommendationController {

	private final RecommendationService service;

	public RecommendationController(RecommendationService service) {
		if (service == null) {
			throw new IllegalArgumentException("RecommendationService must not be null");
		}
		this.service = service;
	}

	@GetMapping
	public List<RecommendationSummaryResponse> list(Authentication authentication) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.listRecommendationResponses(userId);
	}

	@GetMapping("/{recommendationId}")
	public RecommendationDetailResponse getById(Authentication authentication,
			@PathVariable UUID recommendationId) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.getRecommendationDetail(userId, recommendationId);
	}

}
