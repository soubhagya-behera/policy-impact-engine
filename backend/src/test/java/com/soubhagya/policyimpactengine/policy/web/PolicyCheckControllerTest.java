package com.soubhagya.policyimpactengine.policy.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchClaimRejectedException;
import com.soubhagya.policyimpactengine.policy.application.PolicyArchiveService;
import com.soubhagya.policyimpactengine.policy.application.PolicyArchivedException;
import com.soubhagya.policyimpactengine.policy.application.PolicyCheckService;
import com.soubhagya.policyimpactengine.policy.application.PolicyReactivationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyCheckResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 16-B/2 — web-layer tests for {@code POST
 * /api/v1/policies/{policyId}/check} (see DECISIONS.md ADR-034). The
 * check facade is mocked; principal resolution, status codes, response
 * shape, and RFC 7807 problem responses are verified here. Security
 * filters are disabled: the authenticated principal is supplied
 * directly, exactly as the enabled filter chain would publish it. Real
 * observation lives in the service and endpoint integration tests.
 */
@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyCheckControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PolicyService service;

	@MockitoBean
	private PolicyArchiveService archive;

	@MockitoBean
	private PolicyReactivationService reactivation;

	// Phase 16-B/2: the check handler delegates here; mocked so this
	// slice stays isolated from the observation pipeline.
	@MockitoBean
	private PolicyCheckService check;

	// Phase 16-C: PolicyController also serves check history reads; mocked
	// so this slice stays isolated.
	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyCheckHistoryReadService checkHistory;

	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyOverviewReadService overviewService;

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
	void checkReturnsTerminalResultBody() throws Exception {
		UUID policyId = UUID.randomUUID();
		PolicyCheckResponse response = new PolicyCheckResponse(policyId, "NEW_VERSION", 2,
				"a".repeat(64), 3, "SUCCESS");
		when(check.check(userId, policyId)).thenReturn(response);

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", policyId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.policyId").value(policyId.toString()))
				.andExpect(jsonPath("$.outcome").value("NEW_VERSION"))
				.andExpect(jsonPath("$.versionNumber").value(2))
				.andExpect(jsonPath("$.contentHash").value("a".repeat(64)))
				.andExpect(jsonPath("$.changeCount").value(3))
				.andExpect(jsonPath("$.attemptStatus").value("SUCCESS"));

		verify(check).check(userId, policyId);
	}

	@Test
	void checkUnknownPolicyReturnsProblemNotFound() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(check.check(userId, policyId))
				.thenThrow(new NoSuchElementException("Policy " + policyId + " not found"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", policyId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void checkArchivedPolicyReturnsProblemConflict() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(check.check(userId, policyId)).thenThrow(new PolicyArchivedException(policyId));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", policyId)
						.principal(authentication))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Conflict"))
				.andExpect(jsonPath("$.detail").value(
						"Policy " + policyId + " is archived; reactivate it before checking"));
	}

	@Test
	void checkClaimCollisionReturnsProblemConflict() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(check.check(userId, policyId))
				.thenThrow(new PolicyFetchClaimRejectedException(policyId));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", policyId)
						.principal(authentication))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Conflict"))
				.andExpect(jsonPath("$.detail").value("Policy " + policyId
						+ " already has an in-flight check; refusing duplicate work"));
	}

	@Test
	void checkRejectsMalformedIdentifier() throws Exception {
		mockMvc.perform(post("/api/v1/policies/{policyId}/check", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(check);
	}

	@Test
	void checkWithoutPrincipalReturns401() throws Exception {
		mockMvc.perform(post("/api/v1/policies/{policyId}/check", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(check);
	}

	@Test
	void clientSuppliedIdentityCannotOverridePrincipal() throws Exception {
		UUID policyId = UUID.randomUUID();
		UUID foreignId = UUID.randomUUID();
		when(check.check(userId, policyId)).thenReturn(new PolicyCheckResponse(policyId,
				"UNCHANGED", 1, "b".repeat(64), 0, "SKIPPED_UNCHANGED"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", policyId)
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("UNCHANGED"));

		verify(check).check(userId, policyId);
	}
}
