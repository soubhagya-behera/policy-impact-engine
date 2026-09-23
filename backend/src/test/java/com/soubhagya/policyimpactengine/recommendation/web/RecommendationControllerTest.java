package com.soubhagya.policyimpactengine.recommendation.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.recommendation.RecommendationNotFoundException;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.web.dto.RecommendationDetailResponse;
import com.soubhagya.policyimpactengine.recommendation.web.dto.RecommendationSummaryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Web-layer tests for the authenticated recommendation read API.
 * The application service is mocked; principal resolution,
 * delegation, status codes, response shape, and RFC 7807 problem
 * responses are verified here. Security filters are disabled: the
 * authenticated principal is supplied directly, exactly as the
 * enabled filter chain would publish it.
 */
@WebMvcTest(RecommendationController.class)
@AutoConfigureMockMvc(addFilters = false)
class RecommendationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RecommendationService service;

	// Satisfies SecurityConfig wiring in this slice. Filters stay
	// disabled, so the mock never executes.
	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	// Phase 13-C: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private com.soubhagya.policyimpactengine.common.ratelimit.web.RateLimitFilter rateLimitFilter;

	private UUID userId;
	private Authentication authentication;

	@BeforeEach
	void authenticate() {
		userId = UUID.randomUUID();
		authentication = new UsernamePasswordAuthenticationToken(
				new AuthenticatedUser(userId), null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void listDelegatesWithPrincipalUuid() throws Exception {
		RecommendationSummaryResponse response = sampleSummary();
		when(service.listRecommendationResponsesPaged(userId, 0, 20)).thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/recommendations").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(response.id().toString()))
				.andExpect(jsonPath("$[0].assessmentId").value(response.assessmentId().toString()))
				.andExpect(jsonPath("$[0].ruleId").value("REC-SHARING-OPT-OUT"))
				.andExpect(jsonPath("$[0].actionKind").value("OPT_OUT_SHARING"))
				.andExpect(jsonPath("$[0].conceptCode").value("THIRD_PARTY_SHARING"));

		verify(service).listRecommendationResponsesPaged(userId, 0, 20);
	}

	@Test
	void detailDelegatesWithPrincipalUuidAndRecommendationId() throws Exception {
		RecommendationDetailResponse response = sampleDetail();
		when(service.getRecommendationDetail(userId, response.id())).thenReturn(response);

		mockMvc.perform(get("/api/v1/me/recommendations/{recommendationId}", response.id())
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(response.id().toString()))
				.andExpect(jsonPath("$.assessmentId").value(response.assessmentId().toString()))
				.andExpect(jsonPath("$.policyId").value(response.policyId().toString()))
				.andExpect(jsonPath("$.versionNumber").value(2))
				.andExpect(jsonPath("$.ruleId").value("REC-SHARING-OPT-OUT"));

		verify(service).getRecommendationDetail(userId, response.id());
	}

	@Test
	void responseContainsOnlyApprovedFields() throws Exception {
		when(service.listRecommendationResponsesPaged(userId, 0, 20)).thenReturn(List.of(sampleSummary()));

		mockMvc.perform(get("/api/v1/me/recommendations").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").exists())
				.andExpect(jsonPath("$[0].assessmentId").exists())
				.andExpect(jsonPath("$[0].ruleId").exists())
				.andExpect(jsonPath("$[0].ruleOrder").exists())
				.andExpect(jsonPath("$[0].actionKind").exists())
				.andExpect(jsonPath("$[0].conceptCode").exists())
				.andExpect(jsonPath("$[0].personalizedNormalized").exists())
				.andExpect(jsonPath("$[0].personalizedBand").exists())
				.andExpect(jsonPath("$[0].recommendationRulesVersion").exists())
				.andExpect(jsonPath("$[0].createdAt").exists())
				.andExpect(jsonPath("$[0].passwordHash").doesNotExist())
				.andExpect(jsonPath("$[0].password").doesNotExist())
				.andExpect(jsonPath("$[0].email").doesNotExist())
				.andExpect(jsonPath("$[0].accessToken").doesNotExist())
				.andExpect(jsonPath("$[0].token").doesNotExist())
				.andExpect(jsonPath("$[0].user").doesNotExist())
				.andExpect(jsonPath("$[0].userId").doesNotExist())
				.andExpect(jsonPath("$[0].assessment").doesNotExist());
	}

	@Test
	void unknownRecommendationReturns404Problem() throws Exception {
		UUID recommendationId = UUID.randomUUID();
		when(service.getRecommendationDetail(userId, recommendationId))
				.thenThrow(new RecommendationNotFoundException("Recommendation not found"));

		mockMvc.perform(get("/api/v1/me/recommendations/{recommendationId}", recommendationId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail").value("Recommendation not found"));
	}

	@Test
	void malformedRecommendationUuidReturns400Problem() throws Exception {
		mockMvc.perform(get("/api/v1/me/recommendations/{recommendationId}", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(service);
	}

	@Test
	void clientSuppliedUserIdCannotOverridePrincipal() throws Exception {
		UUID foreignId = UUID.randomUUID();
		RecommendationSummaryResponse summary = sampleSummary();
		RecommendationDetailResponse detail = sampleDetail();
		when(service.listRecommendationResponsesPaged(userId, 0, 20)).thenReturn(List.of(summary));
		when(service.getRecommendationDetail(userId, detail.id())).thenReturn(detail);

		mockMvc.perform(get("/api/v1/me/recommendations")
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/me/recommendations/{recommendationId}", detail.id())
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		verify(service).listRecommendationResponsesPaged(userId, 0, 20);
		verify(service).getRecommendationDetail(userId, detail.id());
	}

	@Test
	void listDefaultsToFirstPageOfTwenty() throws Exception {
		RecommendationSummaryResponse response = sampleSummary();
		when(service.listRecommendationResponsesPaged(userId, 0, 20))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/recommendations").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(response.id().toString()));

		verify(service).listRecommendationResponsesPaged(userId, 0, 20);
	}

	@Test
	void listAcceptsExplicitPageAndSize() throws Exception {
		RecommendationSummaryResponse response = sampleSummary();
		when(service.listRecommendationResponsesPaged(userId, 1, 10))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/recommendations")
						.queryParam("page", "1")
						.queryParam("size", "10")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		verify(service).listRecommendationResponsesPaged(userId, 1, 10);
	}

	@Test
	void listRejectsSizeAboveMaximumZeroSizeAndNegativePage() throws Exception {
		mockMvc.perform(get("/api/v1/me/recommendations")
						.queryParam("size", "101")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/me/recommendations")
						.queryParam("size", "0")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/me/recommendations")
						.queryParam("page", "-1")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		verifyNoInteractions(service);
	}

	@Test
	void listRejectsMalformedSizeWithNeutralMessage() throws Exception {
		mockMvc.perform(get("/api/v1/me/recommendations")
						.queryParam("size", "lots")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"))
				.andExpect(jsonPath("$.detail").value("Invalid value for 'size'"));

		verifyNoInteractions(service);
	}

	@Test
	void unauthenticatedListWithInvalidPaginationReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/me/recommendations")
						.queryParam("page", "-1")
						.queryParam("size", "500"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(service);
	}

	private RecommendationSummaryResponse sampleSummary() {
		return new RecommendationSummaryResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"REC-SHARING-OPT-OUT",
				2,
				"OPT_OUT_SHARING",
				"THIRD_PARTY_SHARING",
				100,
				"CRITICAL",
				1,
				Instant.parse("2026-09-18T10:00:00Z"));
	}

	private RecommendationDetailResponse sampleDetail() {
		return new RecommendationDetailResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				2,
				"REC-SHARING-OPT-OUT",
				2,
				"OPT_OUT_SHARING",
				"THIRD_PARTY_SHARING",
				100,
				"CRITICAL",
				1,
				Instant.parse("2026-09-18T10:00:00Z"));
	}

}
