package com.soubhagya.policyimpactengine.impact.web;

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

import com.soubhagya.policyimpactengine.impact.ImpactAssessmentNotFoundException;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentBreakdownResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentDetailResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentSummaryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Web-layer tests for the authenticated impact-assessment read API.
 * The application service is mocked; principal resolution,
 * delegation, status codes, response shape, and RFC 7807 problem
 * responses are verified here. Security filters are disabled: the
 * authenticated principal is supplied directly, exactly as the
 * enabled filter chain would publish it.
 */
@WebMvcTest(ImpactAssessmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class ImpactAssessmentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ImpactAssessmentService service;

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
		ImpactAssessmentSummaryResponse response = sampleSummary();
		when(service.listAssessmentSummariesPaged(userId, 0, 20)).thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/impact-assessments").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(response.id().toString()))
				.andExpect(jsonPath("$[0].policyId").value(response.policyId().toString()))
				.andExpect(jsonPath("$[0].versionNumber").value(2))
				.andExpect(jsonPath("$[0].previousVersionNumber").value(1))
				.andExpect(jsonPath("$[0].aggregateScore").value(80))
				.andExpect(jsonPath("$[0].aggregateBand").value("HIGH"));

		verify(service).listAssessmentSummariesPaged(userId, 0, 20);
	}

	@Test
	void detailDelegatesWithPrincipalUuidAndAssessmentId() throws Exception {
		ImpactAssessmentDetailResponse response = sampleDetail();
		when(service.getAssessmentDetail(userId, response.id())).thenReturn(response);

		mockMvc.perform(get("/api/v1/me/impact-assessments/{assessmentId}", response.id())
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(response.id().toString()))
				.andExpect(jsonPath("$.policyId").value(response.policyId().toString()))
				.andExpect(jsonPath("$.aggregateScore").value(80))
				.andExpect(jsonPath("$.breakdowns").isArray())
				.andExpect(jsonPath("$.breakdowns.length()").value(1))
				.andExpect(jsonPath("$.breakdowns[0].conceptCode").value("LOCATION"));

		verify(service).getAssessmentDetail(userId, response.id());
	}

	@Test
	void listContainsSummariesOnlyWithoutBreakdowns() throws Exception {
		when(service.listAssessmentSummariesPaged(userId, 0, 20)).thenReturn(List.of(sampleSummary()));

		mockMvc.perform(get("/api/v1/me/impact-assessments").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").exists())
				.andExpect(jsonPath("$[0].policyId").exists())
				.andExpect(jsonPath("$[0].versionNumber").exists())
				.andExpect(jsonPath("$[0].previousVersionNumber").exists())
				.andExpect(jsonPath("$[0].aggregateScore").exists())
				.andExpect(jsonPath("$[0].aggregateBand").exists())
				.andExpect(jsonPath("$[0].personalizationRulesVersion").exists())
				.andExpect(jsonPath("$[0].createdAt").exists())
				.andExpect(jsonPath("$[0].breakdowns").doesNotExist())
				.andExpect(jsonPath("$[0].passwordHash").doesNotExist())
				.andExpect(jsonPath("$[0].password").doesNotExist())
				.andExpect(jsonPath("$[0].email").doesNotExist())
				.andExpect(jsonPath("$[0].accessToken").doesNotExist())
				.andExpect(jsonPath("$[0].token").doesNotExist())
				.andExpect(jsonPath("$[0].user").doesNotExist())
				.andExpect(jsonPath("$[0].userId").doesNotExist())
				.andExpect(jsonPath("$[0].ownerId").doesNotExist());
	}

	@Test
	void unknownAssessmentReturns404Problem() throws Exception {
		UUID assessmentId = UUID.randomUUID();
		when(service.getAssessmentDetail(userId, assessmentId))
				.thenThrow(new ImpactAssessmentNotFoundException("Assessment not found"));

		mockMvc.perform(get("/api/v1/me/impact-assessments/{assessmentId}", assessmentId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail").value("Assessment not found"));
	}

	@Test
	void malformedAssessmentUuidReturns400Problem() throws Exception {
		mockMvc.perform(get("/api/v1/me/impact-assessments/{assessmentId}", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(service);
	}

	@Test
	void clientSuppliedUserIdCannotOverridePrincipal() throws Exception {
		UUID foreignId = UUID.randomUUID();
		ImpactAssessmentSummaryResponse summary = sampleSummary();
		ImpactAssessmentDetailResponse detail = sampleDetail();
		when(service.listAssessmentSummariesPaged(userId, 0, 20)).thenReturn(List.of(summary));
		when(service.getAssessmentDetail(userId, detail.id())).thenReturn(detail);

		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/me/impact-assessments/{assessmentId}", detail.id())
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		verify(service).listAssessmentSummariesPaged(userId, 0, 20);
		verify(service).getAssessmentDetail(userId, detail.id());
	}

	@Test
	void listDefaultsToFirstPageOfTwenty() throws Exception {
		ImpactAssessmentSummaryResponse response = sampleSummary();
		when(service.listAssessmentSummariesPaged(userId, 0, 20))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/impact-assessments").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(response.id().toString()));

		verify(service).listAssessmentSummariesPaged(userId, 0, 20);
	}

	@Test
	void listAcceptsExplicitPageAndSize() throws Exception {
		ImpactAssessmentSummaryResponse response = sampleSummary();
		when(service.listAssessmentSummariesPaged(userId, 3, 7))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.queryParam("page", "3")
						.queryParam("size", "7")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		verify(service).listAssessmentSummariesPaged(userId, 3, 7);
	}

	@Test
	void listRejectsSizeAboveMaximumZeroSizeAndNegativePage() throws Exception {
		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.queryParam("size", "101")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.queryParam("size", "0")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.queryParam("page", "-1")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		verifyNoInteractions(service);
	}

	@Test
	void listRejectsMalformedPageWithNeutralMessage() throws Exception {
		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.queryParam("page", "first")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"))
				.andExpect(jsonPath("$.detail").value("Invalid value for 'page'"));

		verifyNoInteractions(service);
	}

	@Test
	void unauthenticatedListWithInvalidPaginationReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.queryParam("page", "-1")
						.queryParam("size", "500"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(service);
	}

	private ImpactAssessmentSummaryResponse sampleSummary() {
		return new ImpactAssessmentSummaryResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				2,
				1,
				80,
				"HIGH",
				1,
				Instant.parse("2026-09-18T10:00:00Z"));
	}

	private ImpactAssessmentDetailResponse sampleDetail() {
		ImpactAssessmentBreakdownResponse breakdown = new ImpactAssessmentBreakdownResponse(
				UUID.randomUUID(),
				"LOCATION",
				"MODIFIED",
				80,
				"CRITICAL",
				1,
				3,
				80,
				"CRITICAL",
				1);
		return new ImpactAssessmentDetailResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				2,
				1,
				80,
				"HIGH",
				1,
				Instant.parse("2026-09-18T10:00:00Z"),
				List.of(breakdown));
	}

}
