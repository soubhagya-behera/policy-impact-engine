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
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Registration and retrieval of tracked policies.
 *
 * <p>Phase 10B-1 adds single-owner assignment through a narrow,
 * fenced {@code policy.application → user.domain} edge
 * (see DECISIONS.md ADR-016): the service reads the persisted
 * {@link User} directly from {@link UserRepository}. No transfer
 * authorization, no REST endpoint, no authentication.
 */
@Service
public class PolicyService {

	private final PolicyRepository repository;
	private final UserRepository userRepository;

	public PolicyService(PolicyRepository repository, UserRepository userRepository) {
		if (repository == null) {
			throw new IllegalArgumentException("PolicyRepository must not be null");
		}
		if (userRepository == null) {
			throw new IllegalArgumentException("UserRepository must not be null");
		}
		this.repository = repository;
		this.userRepository = userRepository;
	}

	@Transactional
	public PolicyResponse register(String name, String url) {
		String validatedUrl = PolicyUrlValidator.validate(url);
		Policy saved = repository.save(new Policy(name, validatedUrl));
		// Flush so that @CreationTimestamp / @UpdateTimestamp are populated
		// before mapping to the response DTO; without flush the timestamps
		// remain null in the first transaction flush.
		repository.flush();
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

	/**
	 * Phase 10B-1 — assigns the single owner of a policy.
	 *
	 * <p>Rules: the policy and the user must both exist; a NULL owner is
	 * assigned; assigning the same owner again is an idempotent no-op;
	 * assigning a different owner is rejected. No transfer authorization
	 * is performed here (a future phase tightens this to authenticated
	 * ownership) and no REST endpoint exposes this operation.
	 *
	 * @param userId identifier of the owning user
	 * @param policyId identifier of the policy to own
	 */
	@Transactional
	public void assignOwner(UUID userId, UUID policyId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		Policy policy = repository.findById(policyId)
				.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new NoSuchElementException("User " + userId + " not found"));
		if (policy.getOwner() == null) {
			policy.setOwner(user);
			repository.save(policy);
			return;
		}
		if (policy.getOwner().getId().equals(userId)) {
			return;
		}
		throw new IllegalStateException("Policy " + policyId + " already has a different owner");
	}

}
