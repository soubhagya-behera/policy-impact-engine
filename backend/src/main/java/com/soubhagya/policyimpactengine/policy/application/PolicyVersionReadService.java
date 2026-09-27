package com.soubhagya.policyimpactengine.policy.application;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionDetailResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionSummaryResponse;

/**
 * Phase 14-B/1 — read-only version-history queries for the
 * authenticated REST surface (see ARCHITECTURE.md §28).
 *
 * <p>Every read is owner-scoped at the repository level through
 * {@code version.policy.owner}: foreign or unknown policies and
 * versions behave as not-found and never reveal whether the row
 * exists. The caller's user id always comes from the authenticated
 * principal via the controller; this service never touches the
 * security context and performs no writes.
 */
@Service
public class PolicyVersionReadService {

	private final PolicyVersionRepository repository;

	public PolicyVersionReadService(PolicyVersionRepository repository) {
		if (repository == null) {
			throw new IllegalArgumentException("PolicyVersionRepository must not be null");
		}
		this.repository = repository;
	}

	/**
	 * Returns one window of the user's version history for a policy
	 * in sequence order (version number ascending). A foreign or
	 * unknown policy yields an empty list. Windowed by
	 * {@code page}/{@code size} (defaults 0/20, maximum 100);
	 * invalid values are rejected, never clamped.
	 */
	@Transactional(readOnly = true)
	public List<VersionSummaryResponse> list(UUID userId, UUID policyId, int page, int size) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		FeedPagination pagination = FeedPagination.of(page, size);
		Sort sort = Sort.by(Sort.Order.asc("versionNumber"));
		return repository.findByPolicy_IdAndPolicy_Owner_Id(policyId, userId,
				pagination.pageRequest(sort)).stream()
				.map(VersionSummaryResponse::from)
				.toList();
	}

	/**
	 * Returns one version snapshot. A foreign or unknown version, or
	 * a version belonging to a different policy than requested,
	 * behaves as not-found.
	 */
	@Transactional(readOnly = true)
	public VersionDetailResponse get(UUID userId, UUID policyId, UUID versionId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		if (versionId == null) {
			throw new IllegalArgumentException("Version id must not be null");
		}
		PolicyVersion version = repository.findByIdAndPolicy_Owner_Id(versionId, userId)
				.orElseThrow(() -> new NoSuchElementException(
						"Policy version " + versionId + " not found"));
		if (!version.getPolicy().getId().equals(policyId)) {
			throw new NoSuchElementException("Policy version " + versionId + " not found");
		}
		return VersionDetailResponse.from(version);
	}
}
