package com.soubhagya.policyimpactengine.policy.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.application.PolicyArchiveService;
import com.soubhagya.policyimpactengine.policy.application.PolicyChangeReadService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionReadService;
import com.soubhagya.policyimpactengine.policy.web.dto.ChangeRecordResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionDiffResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.CreatePolicyRequest;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionDetailResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionSummaryResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUsers;

import jakarta.validation.Valid;

/**
 * Authenticated policy API. Thin: resolves the user id exclusively
 * from the authenticated principal, delegates to the application
 * service, and returns DTOs.
 *
 * <p>Registration assigns the authenticated user as owner; reads are
 * owner-scoped. Identity never comes from the request body, query
 * parameters, headers, or path variables. Cross-user access behaves
 * as not-found.
 *
 * <p>Deletion archives the owned policy through the same
 * principal-only identity; repeats stay {@code 204 No Content}.
 *
 * <p>Phase 11C emits {@code POLICY_REGISTERED} after the policy
 * row commits. Reads, observations, and failed registrations never
 * reach the emit line. No policy URL or content enters metadata.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

	private final PolicyService service;
	private final PolicyArchiveService archive;
	private final PolicyVersionReadService versions;
	private final PolicyChangeReadService changes;
	private final AuditService auditService;

	public PolicyController(PolicyService service, PolicyArchiveService archive,
			PolicyVersionReadService versions,
			PolicyChangeReadService changes, AuditService auditService) {
		if (service == null || archive == null || versions == null || changes == null
				|| auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.service = service;
		this.archive = archive;
		this.versions = versions;
		this.changes = changes;
		this.auditService = auditService;
	}

	@PostMapping
	public ResponseEntity<PolicyResponse> register(Authentication authentication,
			@Valid @RequestBody CreatePolicyRequest request) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		PolicyResponse response = service.register(userId, request.name(), request.url());
		auditService.append(userId, AuditEventType.POLICY_REGISTERED, "POLICY",
				response.id(), AuditMetadata.empty(), null);
		URI location = URI.create("/api/v1/policies/" + response.id());
		return ResponseEntity.created(location).body(response);
	}

	/**
	 * Phase 13-B — paginated owner-scoped listing (ADR-026): bare JSON
	 * array, registration order, windowed by {@code page}/{@code size}
	 * (defaults 0/20, maximum 100). Identity still comes only from the
	 * principal; the principal is resolved before pagination is
	 * validated so unauthenticated callers stay 401.
	 */
	@GetMapping
	public List<PolicyResponse> list(Authentication authentication,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		FeedPagination pagination = FeedPagination.of(page, size);
		return service.listPaged(userId, pagination.page(), pagination.size());
	}

	@GetMapping("/{id}")
	public PolicyResponse getById(Authentication authentication, @PathVariable UUID id) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.get(userId, id);
	}

	/**
	 * Phase 14-C/3 — owner-scoped archive delete (ADR-030). Thin:
	 * resolves the user id exclusively from the authenticated
	 * principal and delegates to {@link PolicyArchiveService}. A real
	 * {@code ACTIVE → ARCHIVED} transition and an already-archived
	 * repeat both yield {@code 204 No Content} with an empty body; a
	 * foreign or unknown policy behaves as not-found. No request
	 * body, no client-supplied identity.
	 */
	@DeleteMapping("/{policyId}")
	public ResponseEntity<Void> delete(Authentication authentication,
			@PathVariable UUID policyId) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		archive.archive(userId, policyId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Phase 14-B/1 — paginated owner-scoped version history (ADR-026):
	 * bare JSON array in version-number ascending order, windowed by
	 * {@code page}/{@code size} (defaults 0/20, maximum 100). Identity
	 * still comes only from the principal; the principal is resolved
	 * before pagination is validated so unauthenticated callers stay
	 * 401. A foreign or unknown policy yields an empty page.
	 */
	@GetMapping("/{policyId}/versions")
	public List<VersionSummaryResponse> listVersions(Authentication authentication,
			@PathVariable UUID policyId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		FeedPagination pagination = FeedPagination.of(page, size);
		return versions.list(userId, policyId, pagination.page(), pagination.size());
	}

	/**
	 * Phase 14-B/1 — single owner-scoped version snapshot. A foreign
	 * or unknown version, or a version belonging to a different
	 * policy than requested, behaves as not-found.
	 */
	@GetMapping("/{policyId}/versions/{versionId}")
	public VersionDetailResponse getVersion(Authentication authentication,
			@PathVariable UUID policyId, @PathVariable UUID versionId) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return versions.get(userId, policyId, versionId);
	}

	/**
	 * Phase 14-B/2 — paginated owner-scoped change history (ADR-026):
	 * bare JSON array of persisted change rows in transition order
	 * (successor version number ascending, then document position
	 * ascending), windowed by {@code page}/{@code size} (defaults 0/20,
	 * maximum 100). Identity still comes only from the principal; the
	 * principal is resolved before pagination is validated so
	 * unauthenticated callers stay 401. A foreign or unknown policy, or
	 * a policy with no change rows, yields an empty page.
	 */
	@GetMapping("/{policyId}/changes")
	public List<ChangeRecordResponse> listChanges(Authentication authentication,
			@PathVariable UUID policyId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		FeedPagination pagination = FeedPagination.of(page, size);
		return changes.list(userId, policyId, pagination.page(), pagination.size());
	}

	/**
	 * Phase 14-B/2 — persisted changes for one adjacent version
	 * transition ({@code to == from + 1}). {@code from} and {@code to}
	 * are 1-based version numbers, not version ids. A foreign, unknown,
	 * or mismatched version behaves as not-found; a non-adjacent range
	 * or a version number below 1 is rejected.
	 */
	@GetMapping("/{policyId}/versions/{from}/diff/{to}")
	public VersionDiffResponse diffVersions(Authentication authentication,
			@PathVariable UUID policyId, @PathVariable int from, @PathVariable int to) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return changes.diff(userId, policyId, from, to);
	}

}
