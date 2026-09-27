package com.soubhagya.policyimpactengine.policy.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link PolicyVersion}.
 *
 * <p>Only the minimum operations required for exact hash-based change
 * detection and diff-to-version integration are exposed: latest-version
 * lookup, single-version lookup by number (predecessor of a new version),
 * sequence-order listing, and standard save support from
 * {@link JpaRepository}. No update or custom delete operations are provided;
 * versions are immutable and append-only.
 *
 * <p>Phase 14-B/1 adds the owner-scoped reads backing the version
 * history REST surface: ownership chains through
 * {@code version.policy.owner}, so cross-user rows behave as
 * not-found. Windowed listings take a caller-supplied
 * {@link Pageable} carrying the exact feed ordering.
 */
public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {

	/**
	 * Returns the current (highest-numbered) version for a policy, if any.
	 */
	Optional<PolicyVersion> findTopByPolicy_IdOrderByVersionNumberDesc(UUID policyId);

	/**
	 * Returns a single version of a policy by its 1-based sequence number,
	 * if present. Used to obtain the immediate predecessor of a newly
	 * created version for diffing.
	 */
	Optional<PolicyVersion> findByPolicy_IdAndVersionNumber(UUID policyId, int versionNumber);

	/**
	 * Returns all versions for a policy in sequence order.
	 */
	List<PolicyVersion> findByPolicy_IdOrderByVersionNumberAsc(UUID policyId);

	/**
	 * Phase 14-B/1 — windowed owner-scoped version listing for the
	 * history feed. A foreign or unknown policy behaves as an empty
	 * result. The {@link Pageable} carries the exact feed ordering
	 * (version number ascending).
	 */
	List<PolicyVersion> findByPolicy_IdAndPolicy_Owner_Id(UUID policyId, UUID ownerId,
			Pageable pageable);

	/**
	 * Phase 14-B/1 — single-version lookup scoped to the owning user
	 * for the version-detail endpoint. A foreign or unknown version
	 * behaves as not-found; callers additionally verify the version
	 * belongs to the requested policy.
	 */
	Optional<PolicyVersion> findByIdAndPolicy_Owner_Id(UUID id, UUID ownerId);

}
