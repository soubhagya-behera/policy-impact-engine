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
import java.util.NoSuchElementException;
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

import com.soubhagya.policyimpactengine.policy.application.PolicyArchiveService;
import com.soubhagya.policyimpactengine.policy.application.PolicyCheckHistoryReadService;
import com.soubhagya.policyimpactengine.policy.application.PolicyCheckService;
import com.soubhagya.policyimpactengine.policy.application.PolicyOverviewReadService;
import com.soubhagya.policyimpactengine.policy.application.PolicyReactivationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyOverviewResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 17-A — web-layer tests for {@code GET
 * /api/v1/policies/{policyId}/overview} (see DECISIONS.md ADR-036). The
 * overview read service is mocked; principal resolution, the single
 * JSON-object shape, the null latest-fact blocks, and the RFC 7807
 * problem responses are verified here. Security filters are disabled: the
 * authenticated principal is supplied directly, exactly as the enabled
 * filter chain would publish it.
 */
@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyOverviewControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PolicyService service;

	@MockitoBean
	private PolicyArchiveService archive;

	@MockitoBean
	private PolicyReactivationService reactivation;

	@MockitoBean
	private PolicyCheckService check;

	@MockitoBean
	private PolicyCheckHistoryReadService checkHistory;

	@MockitoBean
	private PolicyOverviewReadService overviewService;

	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyVersionReadService versions;

	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyChangeReadService changes;

	@MockitoBean
	private com.soubhagya.policyimpactengine.audit.application.AuditService auditService;

	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	@MockitoBean
	private com.soubhagya.policyimpactengine.common.ratelimit.web.RateLimitFilter rateLimitFilter;

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
	void overviewDelegatesPrincipalOnlyAndReturnsSingleObject() throws Exception {
		UUID policyId = UUID.randomUUID();
		UUID attemptId = UUID.randomUUID();
		Instant created = Instant.parse("2026-10-01T09:00:00Z");
		Instant updated = Instant.parse("2026-10-01T09:30:00Z");
		Instant nextCheck = Instant.parse("2026-10-01T10:30:00Z");
		Instant observed = Instant.parse("2026-10-01T09:05:00Z");
		Instant started = Instant.parse("2026-10-01T09:29:00Z");
		Instant completed = Instant.parse("2026-10-01T09:29:04Z");
		Instant assessed = Instant.parse("2026-10-01T09:06:00Z");
		when(overviewService.get(userId, policyId)).thenReturn(new PolicyOverviewResponse(
				policyId, "Acme Privacy Policy", "https://acme.example/privacy", "ACTIVE",
				created, updated, nextCheck,
				new PolicyOverviewResponse.LatestVersion(3, "a".repeat(64), observed),
				new PolicyOverviewResponse.LatestCheck(attemptId, "MANUAL", 2, "SUCCESS",
						null, 200, 4096L, 4000L, started, completed),
				new PolicyOverviewResponse.LatestImpact(75, "HIGH", assessed)));

		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", policyId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$").isMap())
				.andExpect(jsonPath("$.policyId").value(policyId.toString()))
				.andExpect(jsonPath("$.name").value("Acme Privacy Policy"))
				.andExpect(jsonPath("$.url").value("https://acme.example/privacy"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.nextCheckAt").value(nextCheck.toString()))
				.andExpect(jsonPath("$.latestVersion.versionNumber").value(3))
				.andExpect(jsonPath("$.latestVersion.contentHash").value("a".repeat(64)))
				.andExpect(jsonPath("$.latestVersion.observedAt").value(observed.toString()))
				.andExpect(jsonPath("$.latestCheck.id").value(attemptId.toString()))
				.andExpect(jsonPath("$.latestCheck.trigger").value("MANUAL"))
				.andExpect(jsonPath("$.latestCheck.attemptNumber").value(2))
				.andExpect(jsonPath("$.latestCheck.status").value("SUCCESS"))
				.andExpect(jsonPath("$.latestCheck.failureKind").isEmpty())
				.andExpect(jsonPath("$.latestCheck.httpStatus").value(200))
				.andExpect(jsonPath("$.latestCheck.bytesFetched").value(4096))
				.andExpect(jsonPath("$.latestCheck.durationMs").value(4000))
				.andExpect(jsonPath("$.latestVersion.normalizedContent").doesNotExist())
				.andExpect(jsonPath("$.latestCheck.errorMessage").doesNotExist())
				.andExpect(jsonPath("$.latestImpact.aggregateScore").value(75))
				.andExpect(jsonPath("$.latestImpact.band").value("HIGH"))
				.andExpect(jsonPath("$.latestImpact.assessedAt").value(assessed.toString()));

		verify(overviewService).get(userId, policyId);
	}

	@Test
	void overviewRendersMissingLatestFactsAsNullNotZero() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(overviewService.get(userId, policyId)).thenReturn(new PolicyOverviewResponse(
				policyId, "Fresh", "https://fresh.example/privacy", "ACTIVE",
				Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-01T09:00:00Z"),
				Instant.parse("2026-10-01T09:00:00Z"), null, null, null));

		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", policyId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.latestVersion").doesNotExist())
				.andExpect(jsonPath("$.latestCheck").doesNotExist())
				.andExpect(jsonPath("$.latestImpact").doesNotExist());

		verify(overviewService).get(userId, policyId);
	}

	@Test
	void overviewPropagatesForeignOrUnknownAsNotFound() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(overviewService.get(userId, policyId)).thenThrow(
				new NoSuchElementException("Policy " + policyId + " not found"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", policyId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void overviewWithoutPrincipalReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(overviewService);
	}

	@Test
	void overviewRejectsMalformedIdentifier() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(overviewService);
	}
}