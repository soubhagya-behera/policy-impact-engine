package com.soubhagya.policyimpactengine.policy.application;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyCheckHistoryResponse;

/**
 * Phase 16-C — read-only check-history queries for the authenticated
 * REST surface (see DECISIONS.md ADR-035).
 *
 * <p>Every read is owner-scoped at the repository level through
 * {@code attempt.policy.owner}: a foreign or unknown policy yields an
 * empty page, never a leak. Archived owned policies stay fully
 * readable — status gates execution only. Only persisted attempt rows
 * are ever returned, verbatim and uncollapsed; no pipeline, scheduler,
 * retry, fan-out, or audit activity happens here. The caller's user id
 * always comes from the authenticated principal via the controller;
 * this service never touches the security context and performs no
 * writes. Deliberately separate from {@link PolicyCheckService}, which
 * is the execution path.
 */
@Service
public class PolicyCheckHistoryReadService {

	private final PolicyFetchAttemptRepository attempts;

	public PolicyCheckHistoryReadService(PolicyFetchAttemptRepository attempts) {
		if (attempts == null) {
			throw new IllegalArgumentException("Attempt repository must not be null");
		}
		this.attempts = attempts;
	}

	/**
	 * Returns one window of the user's persisted check history for a
	 * policy, newest first ({@code startedAt} descending with the row
	 * id as the deterministic tie-break for equal timestamps). A
	 * foreign or unknown policy, or a policy with no attempts, yields
	 * an empty list. Windowed by {@code page}/{@code size} (defaults
	 * 0/20, maximum 100); invalid values are rejected, never clamped.
	 */
	@Transactional(readOnly = true)
	public List<PolicyCheckHistoryResponse> list(UUID userId, UUID policyId, int page, int size) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		FeedPagination pagination = FeedPagination.of(page, size);
		Sort sort = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));
		return attempts.findByPolicy_IdAndPolicy_Owner_Id(policyId, userId,
				pagination.pageRequest(sort)).stream()
				.map(PolicyCheckHistoryResponse::from)
				.toList();
	}
}
