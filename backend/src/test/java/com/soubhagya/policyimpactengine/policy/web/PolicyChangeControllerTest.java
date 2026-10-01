package com.soubhagya.policyimpactengine.policy.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.policy.application.PolicyChangeReadService;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionReadService;
import com.soubhagya.policyimpactengine.policy.web.dto.ChangeRecordResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionDiffResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 14-B/2 — web-layer tests for the change-history reads. The
 * application service is mocked; request binding, principal
 * resolution, pagination, adjacency validation mapping, status codes,
 * response shape, and RFC 7807 problem responses are verified here.
 * Security filters are disabled: the authenticated principal is
 * supplied directly, exactly as the enabled filter chain would publish
 * it.
 */
@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyChangeControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PolicyService service;

	// Phase 14-C/3: PolicyController also serves the archive delete;
	// mocked so this slice stays isolated.
	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyArchiveService archive;

	// Phase 16-A/2: PolicyController also serves reactivation; mocked
	// so this slice stays isolated.
	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyReactivationService reactivation;

	@MockitoBean
	private PolicyVersionReadService versions;

	@MockitoBean
	private PolicyChangeReadService changes;

	// Phase 11C: PolicyController emits POLICY_REGISTERED post-commit;
	// mocked so this slice stays isolated from the audit chain.
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
	void listChangesReturnsPersistedFactsOnly() throws Exception {
		UUID policyId = UUID.randomUUID();
		UUID newVersionId = UUID.randomUUID();
		ChangeRecordResponse first = new ChangeRecordResponse(
				UUID.randomUUID(), PolicyChangeType.MODIFIED, "old one", "new one", 0, 2,
				newVersionId);
		ChangeRecordResponse second = new ChangeRecordResponse(
				UUID.randomUUID(), PolicyChangeType.ADDED, null, "brand new", 1, 2,
				newVersionId);
		when(changes.list(userId, policyId, 0, 20))
				.thenReturn(List.of(first, second));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(first.id().toString()))
				.andExpect(jsonPath("$[0].changeType").value("MODIFIED"))
				.andExpect(jsonPath("$[0].oldText").value("old one"))
				.andExpect(jsonPath("$[0].newText").value("new one"))
				.andExpect(jsonPath("$[0].changeOrder").value(0))
				.andExpect(jsonPath("$[0].versionNumber").value(2))
				.andExpect(jsonPath("$[0].newVersionId").value(newVersionId.toString()))
				.andExpect(jsonPath("$[0].policyId").doesNotExist())
				.andExpect(jsonPath("$[0].normalizedContent").doesNotExist())
				.andExpect(jsonPath("$[0].contentHash").doesNotExist())
				.andExpect(jsonPath("$[1].changeType").value("ADDED"))
				.andExpect(jsonPath("$[1].oldText").isEmpty())
				.andExpect(jsonPath("$[1].newText").value("brand new"))
				.andExpect(jsonPath("$[1].changeOrder").value(1));

		verify(changes).list(userId, policyId, 0, 20);
	}

	@Test
	void listChangesAcceptsExplicitPageAndSizeAndMaximum() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(changes.list(userId, policyId, 1, 5)).thenReturn(List.of());
		when(changes.list(userId, policyId, 0, 100)).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.queryParam("page", "1")
						.queryParam("size", "5")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.queryParam("size", "100")
						.principal(authentication))
				.andExpect(status().isOk());

		verify(changes).list(userId, policyId, 1, 5);
		verify(changes).list(userId, policyId, 0, 100);
	}

	@Test
	void listChangesRejectsInvalidPageAndSize() throws Exception {
		UUID policyId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.queryParam("size", "101")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.queryParam("size", "0")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.queryParam("page", "-1")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		verifyNoInteractions(changes);
	}

	@Test
	void listChangesRejectsMalformedSizeAndPolicyId() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", UUID.randomUUID())
						.queryParam("size", "many")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"))
				.andExpect(jsonPath("$.detail").value("Invalid value for 'size'"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(changes);
	}

	@Test
	void foreignPolicyListsEmpty() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(changes.list(userId, policyId, 0, 20)).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		verify(changes).list(userId, policyId, 0, 20);
	}

	@Test
	void anonymousChangeReadsReturn401() throws Exception {
		UUID policyId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.queryParam("page", "-1")
						.queryParam("size", "500"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 1, 2))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(changes);
	}

	@Test
	void clientSuppliedUserIdCannotOverridePrincipal() throws Exception {
		UUID foreignId = UUID.randomUUID();
		UUID policyId = UUID.randomUUID();
		UUID newVersionId = UUID.randomUUID();
		ChangeRecordResponse row = new ChangeRecordResponse(
				UUID.randomUUID(), PolicyChangeType.REMOVED, "gone", null, 0, 2,
				newVersionId);
		VersionDiffResponse diff = new VersionDiffResponse(
				policyId, 1, 2, UUID.randomUUID(), newVersionId, List.of(row));
		when(changes.list(userId, policyId, 0, 20)).thenReturn(List.of(row));
		when(changes.diff(userId, policyId, 1, 2)).thenReturn(diff);

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.queryParam("userId", foreignId.toString())
						.queryParam("ownerId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 1, 2)
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		verify(changes).list(userId, policyId, 0, 20);
		verify(changes).diff(userId, policyId, 1, 2);
	}

	@Test
	void adjacentDiffReturnsPersistedTransition() throws Exception {
		UUID policyId = UUID.randomUUID();
		UUID fromId = UUID.randomUUID();
		UUID toId = UUID.randomUUID();
		ChangeRecordResponse row = new ChangeRecordResponse(
				UUID.randomUUID(), PolicyChangeType.MODIFIED, "old", "new", 0, 2, toId);
		VersionDiffResponse diff = new VersionDiffResponse(
				policyId, 1, 2, fromId, toId, List.of(row));
		when(changes.diff(userId, policyId, 1, 2)).thenReturn(diff);

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 1, 2)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policyId").value(policyId.toString()))
				.andExpect(jsonPath("$.fromVersion").value(1))
				.andExpect(jsonPath("$.toVersion").value(2))
				.andExpect(jsonPath("$.fromVersionId").value(fromId.toString()))
				.andExpect(jsonPath("$.toVersionId").value(toId.toString()))
				.andExpect(jsonPath("$.changes").isArray())
				.andExpect(jsonPath("$.changes.length()").value(1))
				.andExpect(jsonPath("$.changes[0].id").value(row.id().toString()))
				.andExpect(jsonPath("$.changes[0].changeType").value("MODIFIED"))
				.andExpect(jsonPath("$.changes[0].oldText").value("old"))
				.andExpect(jsonPath("$.changes[0].newText").value("new"))
				.andExpect(jsonPath("$.changes[0].changeOrder").value(0))
				.andExpect(jsonPath("$.changes[0].versionNumber").value(2))
				.andExpect(jsonPath("$.changes[0].newVersionId").value(toId.toString()));

		verify(changes).diff(userId, policyId, 1, 2);
	}

	@Test
	void nonAdjacentDiffReturns400() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(changes.diff(userId, policyId, 1, 3))
				.thenThrow(new IllegalArgumentException(
						"Only adjacent versions can be diffed: expected to == from + 1, got from=1 to=3"));
		when(changes.diff(userId, policyId, 2, 2))
				.thenThrow(new IllegalArgumentException(
						"Only adjacent versions can be diffed: expected to == from + 1, got from=2 to=2"));
		when(changes.diff(userId, policyId, 3, 1))
				.thenThrow(new IllegalArgumentException(
						"Only adjacent versions can be diffed: expected to == from + 1, got from=3 to=1"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 1, 3)
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(
						org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 2, 2)
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 3, 1)
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));
	}

	@Test
	void invalidVersionNumbersReturn400() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(changes.diff(userId, policyId, 0, 1))
				.thenThrow(new IllegalArgumentException("from version must be >= 1, got: 0"));
		when(changes.diff(userId, policyId, -2, -1))
				.thenThrow(new IllegalArgumentException("from version must be >= 1, got: -2"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 0, 1)
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, -2, -1)
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));
	}

	@Test
	void diffRejectsMalformedVersionNumbers() throws Exception {
		UUID policyId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, "one", 2)
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 1, "two")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						"not-a-uuid", 1, 2)
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(changes);
	}

	@Test
	void unknownForeignAndMismatchedDiffsReturn404() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(changes.diff(userId, policyId, 1, 2))
				.thenThrow(new NoSuchElementException(
						"Policy version 2 not found for policy " + policyId));
		when(changes.diff(userId, policyId, 2, 3))
				.thenThrow(new NoSuchElementException(
						"Policy version 2 not found for policy " + policyId));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 1, 2)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(
						org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail")
						.value("Policy version 2 not found for policy " + policyId));

		// Mismatched predecessor/successor linkage behaves as not-found.
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 2, 3)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}
}
