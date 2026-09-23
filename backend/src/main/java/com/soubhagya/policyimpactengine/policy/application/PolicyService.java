package com.soubhagya.policyimpactengine.policy.application;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
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
 *
 * <p>Authenticated policy hardening: registration assigns the
 * authenticated user as owner, and every read is owner-scoped at
 * the repository level. Cross-user access behaves as not-found and
 * never reveals whether the row exists. Ownership is immutable
 * through this API: no transfer operation is exposed.
 *
 * <p>Phase 11C emits {@code POLICY_OWNER_ASSIGNED} through a narrow
 * fenced {@code policy.application → audit.application} edge: the
 * non-transactional facade commits the assignment first, then
 * appends the audit event, so an audit failure never rolls back
 * the committed ownership (see DECISIONS.md ADR-022). The actor is
 * the assigned user, per ADR-023; no authentication context enters
 * this internal operation.
 */
@Service
public class PolicyService {

	private final PolicyRepository repository;
	private final UserRepository userRepository;
	private final TransactionTemplate assignOwnerTransaction;
	private final AuditService auditService;

	public PolicyService(PolicyRepository repository, UserRepository userRepository,
			PlatformTransactionManager transactionManager, AuditService auditService) {
		if (repository == null) {
			throw new IllegalArgumentException("PolicyRepository must not be null");
		}
		if (userRepository == null) {
			throw new IllegalArgumentException("UserRepository must not be null");
		}
		if (transactionManager == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.repository = repository;
		this.userRepository = userRepository;
		this.assignOwnerTransaction = new TransactionTemplate(transactionManager);
		this.auditService = auditService;
	}

	/**
	 * Registers a policy owned by the given user. The owner always
	 * comes from the authenticated principal via the controller; the
	 * request carries no owner identity.
	 */
	@Transactional
	public PolicyResponse register(UUID userId, String name, String url) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		String validatedUrl = PolicyUrlValidator.validate(url);
		User owner = userRepository.findById(userId)
				.orElseThrow(() -> new NoSuchElementException("User " + userId + " not found"));
		Policy policy = new Policy(name, validatedUrl);
		policy.setOwner(owner);
		Policy saved = repository.save(policy);
		// Flush so that @CreationTimestamp / @UpdateTimestamp are populated
		// before mapping to the response DTO; without flush the timestamps
		// remain null in the first transaction flush.
		repository.flush();
		return PolicyResponse.from(saved);
	}

	/**
	 * Returns the user's policy. A foreign id behaves as not-found.
	 */
	@Transactional(readOnly = true)
	public PolicyResponse get(UUID userId, UUID id) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return repository.findByIdAndOwner_Id(id, userId)
				.map(PolicyResponse::from)
				.orElseThrow(() -> new NoSuchElementException("Policy " + id + " not found"));
	}

	/**
	 * Returns the user's policies in registration order, oldest first.
	 */
	@Transactional(readOnly = true)
	public List<PolicyResponse> list(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return repository.findByOwner_IdOrderByCreatedAtAscIdAsc(userId).stream()
				.map(PolicyResponse::from)
				.toList();
	}

	/**
	 * Phase 13-B — paginated owner-scoped policy listing (ADR-026).
	 * Same rows and registration order as {@link #list(UUID)}, windowed
	 * by {@code page}/{@code size} (defaults 0/20, maximum 100).
	 * The Sort carries the exact listing order (createdAt ASC, id ASC)
	 * so the repository method needs no embedded ordering. The existing
	 * unbounded method stays for internal callers.
	 */
	@Transactional(readOnly = true)
	public List<PolicyResponse> listPaged(UUID userId, int page, int size) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		FeedPagination pagination = FeedPagination.of(page, size);
		Sort sort = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
		return repository.findByOwner_Id(userId, pagination.pageRequest(sort)).stream()
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
	 * <p>Phase 11C — returns whether the assignment actually occurred
	 * and emits {@code POLICY_OWNER_ASSIGNED} only then, after the
	 * assignment commits. Same-owner no-ops and all failures stay
	 * silent.
	 *
	 * @param userId identifier of the owning user
	 * @param policyId identifier of the policy to own
	 * @return true when a NULL owner became the given user
	 */
	public boolean assignOwner(UUID userId, UUID policyId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		boolean assigned = assignOwnerTransaction.execute(
				status -> assignOwnerOnce(userId, policyId));
		if (assigned) {
			auditService.append(userId, AuditEventType.POLICY_OWNER_ASSIGNED, "POLICY",
					policyId, AuditMetadata.empty(), null);
		}
		return assigned;
	}

	private boolean assignOwnerOnce(UUID userId, UUID policyId) {
		Policy policy = repository.findById(policyId)
				.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new NoSuchElementException("User " + userId + " not found"));
		if (policy.getOwner() == null) {
			policy.setOwner(user);
			repository.save(policy);
			return true;
		}
		if (policy.getOwner().getId().equals(userId)) {
			return false;
		}
		throw new IllegalStateException("Policy " + policyId + " already has a different owner");
	}

}
