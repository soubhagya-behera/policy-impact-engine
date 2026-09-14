package com.soubhagya.policyimpactengine.policy.application;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.web.PolicyUrlValidator;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyResponse;

/**
 * Registration and retrieval of tracked policies.
 */
@Service
public class PolicyService {

	private final PolicyRepository repository;

	public PolicyService(PolicyRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public PolicyResponse register(String name, String url) {
		String validatedUrl = PolicyUrlValidator.validate(url);
		Policy saved = repository.save(new Policy(name, validatedUrl));
		return PolicyResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public PolicyResponse getById(UUID id) {
		return repository.findById(id)
				.map(PolicyResponse::from)
				.orElseThrow(() -> new NoSuchElementException("Policy " + id + " not found"));
	}

	@Transactional(readOnly = true)
	public List<PolicyResponse> list() {
		return repository.findAll().stream()
				.map(PolicyResponse::from)
				.toList();
	}

}
