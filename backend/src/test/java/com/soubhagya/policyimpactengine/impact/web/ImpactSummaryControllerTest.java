package com.soubhagya.policyimpactengine.impact.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.soubhagya.policyimpactengine.impact.ImpactSummaryReadService;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentSummaryResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactSummaryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 14-B/4 — web-layer tests for the impact-summary read. The
 * application service is mocked; principal resolution, delegation,
 * status codes, response shape, and RFC 7807 problem responses are
 * verified here. Security filters are disabled: the authenticated
 * principal is supplied directly, exactly as the enabled filter
 * chain would publish it.
 */
@WebMvcTest(ImpactSummaryController.class)
@AutoConfigureMockMvc(addFilters = false)
class ImpactSummaryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ImpactSummaryReadService service;

	// Satisfies SecurityConfig wiring in this slice. Filters stay
	// disabled, so the mock never executes.
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
	void getSummaryReturnsPopulatedShape() throws Exception {
		ImpactAssessmentSummaryResponse latest = new ImpactAssessmentSummaryResponse(
				UUID.randomUUID(), UUID.randomUUID(), 3, 2, 90, "CRITICAL", 1,
				Instant.parse("2026-09-14T10:00:00Z"));
		ImpactSummaryResponse response = new ImpactSummaryResponse(
				3,
				List.of(
						new ImpactSummaryResponse.BandCount("CRITICAL", 1),
						new ImpactSummaryResponse.BandCount("HIGH", 2)),
				90, 2, latest);
		when(service.getSummary(userId)).thenReturn(response);

		mockMvc.perform(get("/api/v1/me/impact-summary").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(3))
				.andExpect(jsonPath("$.assessmentsByBand").isArray())
				.andExpect(jsonPath("$.assessmentsByBand.length()").value(2))
				.andExpect(jsonPath("$.assessmentsByBand[0].band").value("CRITICAL"))
				.andExpect(jsonPath("$.assessmentsByBand[0].count").value(1))
				.andExpect(jsonPath("$.assessmentsByBand[1].band").value("HIGH"))
				.andExpect(jsonPath("$.assessmentsByBand[1].count").value(2))
				.andExpect(jsonPath("$.maxAggregateScore").value(90))
				.andExpect(jsonPath("$.actionableRecommendations").value(2))
				.andExpect(jsonPath("$.latest.id").value(latest.id().toString()))
				.andExpect(jsonPath("$.latest.policyId").value(latest.policyId().toString()))
				.andExpect(jsonPath("$.latest.versionNumber").value(3))
				.andExpect(jsonPath("$.latest.previousVersionNumber").value(2))
				.andExpect(jsonPath("$.latest.aggregateScore").value(90))
				.andExpect(jsonPath("$.latest.aggregateBand").value("CRITICAL"))
				.andExpect(jsonPath("$.latest.personalizationRulesVersion").value(1))
				.andExpect(jsonPath("$.latest.createdAt").exists());

		verify(service).getSummary(userId);
	}

	@Test
	void getSummaryReturnsEmptyState() throws Exception {
		when(service.getSummary(userId))
				.thenReturn(new ImpactSummaryResponse(0, List.of(), 0, 0, null));

		mockMvc.perform(get("/api/v1/me/impact-summary").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(0))
				.andExpect(jsonPath("$.assessmentsByBand").isArray())
				.andExpect(jsonPath("$.assessmentsByBand.length()").value(0))
				.andExpect(jsonPath("$.maxAggregateScore").value(0))
				.andExpect(jsonPath("$.actionableRecommendations").value(0))
				.andExpect(jsonPath("$.latest").value(nullValue()));

		verify(service).getSummary(userId);
	}

	@Test
	void anonymousSummaryReadReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/me/impact-summary"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(service);
	}

	@Test
	void clientSuppliedUserIdCannotOverridePrincipal() throws Exception {
		UUID foreignId = UUID.randomUUID();
		when(service.getSummary(userId))
				.thenReturn(new ImpactSummaryResponse(0, List.of(), 0, 0, null));

		mockMvc.perform(get("/api/v1/me/impact-summary")
						.queryParam("userId", foreignId.toString())
						.queryParam("ownerId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(0));

		verify(service).getSummary(userId);
	}
}
