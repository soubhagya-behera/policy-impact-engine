package com.soubhagya.policyimpactengine.policy.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.web.dto.CreatePolicyRequest;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyResponse;

import jakarta.validation.Valid;

/**
 * Policy registration API. Thin: parses input, delegates to the
 * application service, and returns DTOs.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

	private final PolicyService service;

	public PolicyController(PolicyService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<PolicyResponse> register(@Valid @RequestBody CreatePolicyRequest request) {
		PolicyResponse response = service.register(request.name(), request.url());
		URI location = URI.create("/api/v1/policies/" + response.id());
		return ResponseEntity.created(location).body(response);
	}

	@GetMapping
	public List<PolicyResponse> list() {
		return service.list();
	}

	@GetMapping("/{id}")
	public PolicyResponse getById(@PathVariable UUID id) {
		return service.getById(id);
	}

}
