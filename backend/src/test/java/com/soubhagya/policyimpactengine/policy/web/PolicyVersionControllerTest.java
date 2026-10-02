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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionReadService;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionDetailResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionSummaryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 14-B/1 — web-layer tests for the version-history reads. The
 * application service is mocked; request binding, principal
 * resolution, pagination, status codes, response shape, and RFC 7807
 * problem responses are verified here. Security filters are disabled:
 * the authenticated principal is supplied directly, exactly as the
 * enabled filter chain would publish it.
 */
@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyVersionControllerTest {

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

	// Phase 16-B/2: PolicyController also serves the manual check; mocked
	// so this slice stays isolated.
	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyCheckService check;

	// Phase 16-C: PolicyController also serves check history reads; mocked
	// so this slice stays isolated.
	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyCheckHistoryReadService checkHistory;

	@MockitoBean
	private PolicyVersionReadService versions;

	// Phase 14-B/2: PolicyController also serves the change-history
	// reads; mocked so this slice stays isolated.
	@MockitoBean
	private com.soubhagya.policyimpactengine.policy.application.PolicyChangeReadService changes;

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
	void listVersionsReturnsSummariesWithoutContent() throws Exception {
		UUID policyId = UUID.randomUUID();
		VersionSummaryResponse first = sampleSummary(policyId, 1);
		VersionSummaryResponse second = sampleSummary(policyId, 2);
		when(versions.list(userId, policyId, 0, 20))
				.thenReturn(List.of(first, second));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(first.id().toString()))
				.andExpect(jsonPath("$[0].policyId").value(policyId.toString()))
				.andExpect(jsonPath("$[0].versionNumber").value(1))
				.andExpect(jsonPath("$[0].contentHash").value(first.contentHash()))
				.andExpect(jsonPath("$[0].observedAt").exists())
				.andExpect(jsonPath("$[0].normalizedContent").doesNotExist())
				.andExpect(jsonPath("$[1].versionNumber").value(2));

		verify(versions).list(userId, policyId, 0, 20);
	}

	@Test
	void listVersionsAcceptsExplicitPageAndSize() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(versions.list(userId, policyId, 1, 5)).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.queryParam("page", "1")
						.queryParam("size", "5")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		verify(versions).list(userId, policyId, 1, 5);
	}

	@Test
	void listVersionsAcceptsMaximumSize() throws Exception {
		UUID policyId = UUID.randomUUID();
		when(versions.list(userId, policyId, 0, 100)).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.queryParam("size", "100")
						.principal(authentication))
				.andExpect(status().isOk());

		verify(versions).list(userId, policyId, 0, 100);
	}

	@Test
	void listVersionsRejectsInvalidPageAndSize() throws Exception {
		UUID policyId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.queryParam("size", "101")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.queryParam("size", "0")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.queryParam("page", "-1")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		verifyNoInteractions(versions);
	}

	@Test
	void listVersionsRejectsMalformedSize() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", UUID.randomUUID())
						.queryParam("size", "many")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"))
				.andExpect(jsonPath("$.detail").value("Invalid value for 'size'"));

		verifyNoInteractions(versions);
	}

	@Test
	void listVersionsRejectsMalformedPolicyId() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(versions);
	}

	@Test
	void anonymousVersionReadsReturn401() throws Exception {
		UUID policyId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.queryParam("page", "-1")
						.queryParam("size", "500"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						policyId, UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(versions);
	}

	@Test
	void clientSuppliedUserIdCannotOverridePrincipal() throws Exception {
		UUID foreignId = UUID.randomUUID();
		UUID policyId = UUID.randomUUID();
		VersionSummaryResponse summary = sampleSummary(policyId, 1);
		VersionDetailResponse detail = sampleDetail(policyId, 1);
		when(versions.list(userId, policyId, 0, 20)).thenReturn(List.of(summary));
		when(versions.get(userId, policyId, detail.id())).thenReturn(detail);

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.queryParam("userId", foreignId.toString())
						.queryParam("ownerId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						policyId, detail.id())
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		verify(versions).list(userId, policyId, 0, 20);
		verify(versions).get(userId, policyId, detail.id());
	}

	@Test
	void getVersionReturnsSnapshotWithContent() throws Exception {
		UUID policyId = UUID.randomUUID();
		VersionDetailResponse detail = sampleDetail(policyId, 1);
		when(versions.get(userId, policyId, detail.id())).thenReturn(detail);

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						policyId, detail.id())
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(detail.id().toString()))
				.andExpect(jsonPath("$.policyId").value(policyId.toString()))
				.andExpect(jsonPath("$.versionNumber").value(1))
				.andExpect(jsonPath("$.contentHash").value(detail.contentHash()))
				.andExpect(jsonPath("$.observedAt").exists())
				.andExpect(jsonPath("$.normalizedContent").value("first content"));

		verify(versions).get(userId, policyId, detail.id());
	}

	@Test
	void unknownForeignAndMismatchedVersionsReturn404() throws Exception {
		UUID policyId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		when(versions.get(userId, policyId, versionId))
				.thenThrow(new NoSuchElementException(
						"Policy version " + versionId + " not found"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						policyId, versionId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(
						org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail")
						.value("Policy version " + versionId + " not found"));
	}

	@Test
	void getVersionRejectsMalformedIdentifiers() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						"not-a-uuid", UUID.randomUUID())
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						UUID.randomUUID(), "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(versions);
	}

	private VersionSummaryResponse sampleSummary(UUID policyId, int versionNumber) {
		return new VersionSummaryResponse(
				UUID.randomUUID(),
				policyId,
				versionNumber,
				"hash-" + versionNumber,
				Instant.parse("2026-09-14T10:00:00Z"));
	}

	private VersionDetailResponse sampleDetail(UUID policyId, int versionNumber) {
		return new VersionDetailResponse(
				UUID.randomUUID(),
				policyId,
				versionNumber,
				"hash-" + versionNumber,
				Instant.parse("2026-09-14T10:00:00Z"),
				"first content");
	}
}
