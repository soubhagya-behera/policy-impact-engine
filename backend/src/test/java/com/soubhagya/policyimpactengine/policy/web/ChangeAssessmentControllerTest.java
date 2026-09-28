package com.soubhagya.policyimpactengine.policy.web;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentNotFoundException;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentBreakdownResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentDetailResponse;
import com.soubhagya.policyimpactengine.policy.application.ChangeAssessmentReadService;
import com.soubhagya.policyimpactengine.policy.web.dto.ChangeAssessmentResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.ChangeRecordResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 14-B/3 — web-layer tests for the change-assessment read. The
 * application service is mocked; request binding, principal
 * resolution, delegation, status codes, response shape, and RFC 7807
 * problem responses are verified here. Security filters are disabled:
 * the authenticated principal is supplied directly, exactly as the
 * enabled filter chain would publish it.
 */
@WebMvcTest(ChangeAssessmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChangeAssessmentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ChangeAssessmentReadService service;

	// Phase 8B: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	// Phase 13-C: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private com.soubhagya.policyimpactengine.common.ratelimit.web.RateLimitFilter rateLimitFilter;

	// Phase 13-D: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

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
	void getAssessmentReturnsChangeContextWithFilteredDetail() throws Exception {
		UUID changeId = UUID.randomUUID();
		UUID newVersionId = UUID.randomUUID();
		UUID policyId = UUID.randomUUID();
		UUID assessmentId = UUID.randomUUID();
		ChangeRecordResponse change = new ChangeRecordResponse(
				changeId, PolicyChangeType.MODIFIED, "old", "new", 0, 2, newVersionId);
		ImpactAssessmentBreakdownResponse breakdown = new ImpactAssessmentBreakdownResponse(
				UUID.randomUUID(), "LOCATION", "MODIFIED", 80, "HIGH", 1, 3, 80, "HIGH", 1);
		ImpactAssessmentDetailResponse detail = new ImpactAssessmentDetailResponse(
				assessmentId, policyId, 2, 1, 80, "HIGH", 1,
				Instant.parse("2026-09-14T10:00:00Z"), List.of(breakdown));
		when(service.getForChange(userId, changeId))
				.thenReturn(new ChangeAssessmentResponse(change, detail));

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.change.id").value(changeId.toString()))
				.andExpect(jsonPath("$.change.changeType").value("MODIFIED"))
				.andExpect(jsonPath("$.change.oldText").value("old"))
				.andExpect(jsonPath("$.change.newText").value("new"))
				.andExpect(jsonPath("$.change.changeOrder").value(0))
				.andExpect(jsonPath("$.change.versionNumber").value(2))
				.andExpect(jsonPath("$.change.newVersionId").value(newVersionId.toString()))
				.andExpect(jsonPath("$.assessment.id").value(assessmentId.toString()))
				.andExpect(jsonPath("$.assessment.policyId").value(policyId.toString()))
				.andExpect(jsonPath("$.assessment.versionNumber").value(2))
				.andExpect(jsonPath("$.assessment.previousVersionNumber").value(1))
				.andExpect(jsonPath("$.assessment.aggregateScore").value(80))
				.andExpect(jsonPath("$.assessment.aggregateBand").value("HIGH"))
				.andExpect(jsonPath("$.assessment.personalizationRulesVersion").value(1))
				.andExpect(jsonPath("$.assessment.createdAt").exists())
				.andExpect(jsonPath("$.assessment.breakdowns").isArray())
				.andExpect(jsonPath("$.assessment.breakdowns.length()").value(1))
				.andExpect(jsonPath("$.assessment.breakdowns[0].conceptCode").value("LOCATION"))
				.andExpect(jsonPath("$.assessment.breakdowns[0].changeType").value("MODIFIED"))
				.andExpect(jsonPath("$.assessment.breakdowns[0].systemNormalized").value(80))
				.andExpect(jsonPath("$.assessment.breakdowns[0].systemBand").value("HIGH"))
				.andExpect(jsonPath("$.assessment.breakdowns[0].effectiveSensitivity").value(3))
				.andExpect(jsonPath("$.assessment.breakdowns[0].personalizedNormalized").value(80))
				.andExpect(jsonPath("$.assessment.breakdowns[0].personalizedBand").value("HIGH"));

		verify(service).getForChange(userId, changeId);
	}

	@Test
	void anonymousAssessmentReadReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(service);
	}

	@Test
	void getAssessmentRejectsMalformedChangeId() throws Exception {
		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(service);
	}

	@Test
	void clientSuppliedUserIdCannotOverridePrincipal() throws Exception {
		UUID foreignId = UUID.randomUUID();
		UUID changeId = UUID.randomUUID();
		ChangeRecordResponse change = new ChangeRecordResponse(
				changeId, PolicyChangeType.ADDED, null, "new", 1, 2, UUID.randomUUID());
		ImpactAssessmentDetailResponse detail = new ImpactAssessmentDetailResponse(
				UUID.randomUUID(), UUID.randomUUID(), 2, 1, 42, "MEDIUM", 1,
				Instant.parse("2026-09-14T10:00:00Z"), List.of());
		when(service.getForChange(userId, changeId))
				.thenReturn(new ChangeAssessmentResponse(change, detail));

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.queryParam("userId", foreignId.toString())
						.queryParam("ownerId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.change.id").value(changeId.toString()));

		verify(service).getForChange(userId, changeId);
	}

	@Test
	void unknownForeignAndMissingAssessmentReturn404() throws Exception {
		UUID changeId = UUID.randomUUID();
		when(service.getForChange(userId, changeId))
				.thenThrow(new ImpactAssessmentNotFoundException(
						"Change " + changeId + " not found"));

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(
						org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail")
						.value("Change " + changeId + " not found"));
	}

	@Test
	void ownedChangeWithoutAssessmentReturns404() throws Exception {
		UUID changeId = UUID.randomUUID();
		when(service.getForChange(userId, changeId))
				.thenThrow(new ImpactAssessmentNotFoundException(
						"Assessment not found for change " + changeId));

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail")
						.value("Assessment not found for change " + changeId));
	}
}
