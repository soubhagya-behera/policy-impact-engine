package com.soubhagya.policyimpactengine.policy.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

}
