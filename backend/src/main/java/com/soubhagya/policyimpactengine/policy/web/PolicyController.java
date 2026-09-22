package com.soubhagya.policyimpactengine.policy.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.web.dto.CreatePolicyRequest;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyResponse;
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
 * <p>Phase 11C emits {@code POLICY_REGISTERED} after the policy
 * row commits. Reads, observations, and failed registrations never
 * reach the emit line. No policy URL or content enters metadata.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

	private final PolicyService service;
	private final AuditService auditService;

	public PolicyController(PolicyService service, AuditService auditService) {
		if (service == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.service = service;
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

	@GetMapping
	public List<PolicyResponse> list(Authentication authentication) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.list(userId);
	}

	@GetMapping("/{id}")
	public PolicyResponse getById(Authentication authentication, @PathVariable UUID id) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.get(userId, id);
	}

}
