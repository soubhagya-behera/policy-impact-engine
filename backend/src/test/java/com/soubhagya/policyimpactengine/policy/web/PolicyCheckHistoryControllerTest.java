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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.policy.application.PolicyArchiveService;
import com.soubhagya.policyimpactengine.policy.application.PolicyCheckHistoryReadService;
import com.soubhagya.policyimpactengine.policy.application.PolicyCheckService;
import com.soubhagya.policyimpactengine.policy.application.PolicyReactivationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyCheckHistoryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 16-C — web-layer tests for {@code GET
 * /api/v1/policies/{policyId}/checks} (see DECISIONS.md ADR-035). The
 * history read service is mocked; principal resolution, pagination
 * validation, bare-array shape, and RFC 7807 problem responses are
 * verified here. Security filters are disabled: the authenticated
 * principal is supplied directly, exactly as the enabled filter chain
 * would publish it.
 */
@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyCheckHistoryControllerTest {

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
	void historyDelegatesWithDefaultsAndBareArrayShape() throws Exception {
		UUID policyId = UUID.randomUUID();
		UUID attemptId = UUID.randomUUID();
		Instant started = Instant.parse("2026-10-01T10:00:00Z");
		Instant completed = Instant.parse("2026-10-01T10:00:05Z");
		when(checkHistory.list(userId, policyId, 0, 20)).thenReturn(List.of(
				new PolicyCheckHistoryResponse(attemptId, "MANUAL", 1, "SUCCESS", null, 200,
						4096L, 5000L, null, started, completed)));

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", policyId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(attemptId.toString()))
				.andExpect(jsonPath("$[0].trigger").value("MANUAL"))
				.andExpect(jsonPath("$[0].attemptNumber").value(1))
				.andExpect(jsonPath("$[0].status").value("SUCCESS"))
				.andExpect(jsonPath("$[0].failureKind").isEmpty())
				.andExpect(jsonPath("$[0].totalCount").doesNotExist());

		verify(checkHistory).list(userId, policyId, 0, 20);
	}

	@Test
	void historyForwardsExplicitPageAndSize() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(checkHistory.list(userId, policyId, 2, 5)).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", policyId)
						.queryParam("page", "2")
						.queryParam("size", "5")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		verify(checkHistory).list(userId, policyId, 2, 5);
	}

	@Test
	void invalidPageAndSizeAreRejectedWithoutServiceCall() throws Exception {
		UUID policyId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", policyId)
						.queryParam("page", "-1")
						.principal(authentication))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", policyId)
						.queryParam("size", "0")
						.principal(authentication))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", policyId)
						.queryParam("size", "101")
						.principal(authentication))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", policyId)
						.queryParam("page", "abc")
						.principal(authentication))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(checkHistory);
	}

	@Test
	void historyWithoutPrincipalReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", UUID.randomUUID())
						.queryParam("page", "-1"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(checkHistory);
	}

	@Test
	void historyRejectsMalformedIdentifier() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(checkHistory);
	}
}
