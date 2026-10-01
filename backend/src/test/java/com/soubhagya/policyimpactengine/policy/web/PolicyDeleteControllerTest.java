package com.soubhagya.policyimpactengine.policy.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import com.soubhagya.policyimpactengine.policy.application.PolicyArchiveService;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 14-C/3 — web-layer tests for {@code DELETE
 * /api/v1/policies/{policyId}} (see DECISIONS.md ADR-030). The
 * archive service is mocked; principal resolution, status codes,
 * empty-body shape, and RFC 7807 problem responses are verified
 * here. Security filters are disabled: the authenticated principal
 * is supplied directly, exactly as the enabled filter chain would
 * publish it. Real archiving lives in the service integration
 * tests; the enabled-filter proof lives in the delete integration
 * test.
 */
@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyDeleteControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PolicyService service;

	// Phase 14-C/3: the DELETE handler delegates here; mocked so
	// this slice stays isolated from the archive transaction.
	@MockitoBean
	private PolicyArchiveService archive;

	// Phase 16-A/2: PolicyController also serves reactivation; mocked
	// so this slice stays isolated.
	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyReactivationService reactivation;

	// Phase 16-B/2: PolicyController also serves the manual check; mocked
	// so this slice stays isolated.
	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyCheckService check;

	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyVersionReadService versions;

	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyChangeReadService changes;

	@MockitoBean
	private com.soubhagya.policyimpactengine.audit.application.AuditService auditService;

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
	void deleteActivePolicyReturnsNoContentWithEmptyBody() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(archive.archive(userId, policyId)).thenReturn(true);

		mockMvc.perform(delete("/api/v1/policies/{policyId}", policyId)
						.principal(authentication))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(archive).archive(userId, policyId);
	}

	@Test
	void deleteAlreadyArchivedPolicyReturnsNoContent() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(archive.archive(userId, policyId)).thenReturn(false);

		mockMvc.perform(delete("/api/v1/policies/{policyId}", policyId)
						.principal(authentication))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(archive).archive(userId, policyId);
	}

	@Test
	void deleteUnknownPolicyReturnsProblemNotFound() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(archive.archive(userId, policyId))
				.thenThrow(new NoSuchElementException("Policy " + policyId + " not found"));

		mockMvc.perform(delete("/api/v1/policies/{policyId}", policyId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail").value("Policy " + policyId + " not found"));
	}

	@Test
	void deleteRejectsMalformedIdentifier() throws Exception {
		mockMvc.perform(delete("/api/v1/policies/{policyId}", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(archive);
	}

	@Test
	void deleteWithoutPrincipalReturns401() throws Exception {
		mockMvc.perform(delete("/api/v1/policies/{policyId}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(archive);
	}

	@Test
	void clientSuppliedIdentityCannotOverridePrincipal() throws Exception {
		UUID policyId = UUID.randomUUID();
		UUID foreignId = UUID.randomUUID();
		when(archive.archive(userId, policyId)).thenReturn(true);

		mockMvc.perform(delete("/api/v1/policies/{policyId}", policyId)
						.queryParam("userId", foreignId.toString())
						.queryParam("ownerId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"%s\",\"ownerId\":\"%s\"}".formatted(foreignId, foreignId))
						.principal(authentication))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(archive).archive(userId, policyId);
	}
}
