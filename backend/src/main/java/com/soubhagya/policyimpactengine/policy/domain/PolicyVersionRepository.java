package com.soubhagya.policyimpactengine.policy.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link PolicyVersion}.
 *
 * <p>Only the minimum operations required for exact hash-based change
 * detection are exposed: latest-version lookup and standard save support from
 * {@link JpaRepository}. No update or custom delete operations are provided;
 * versions are immutable and append-only.
 */
public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {

	/**
	 * Returns the current (highest-numbered) version for a policy, if any.
	 */
	Optional<PolicyVersion> findTopByPolicy_IdOrderByVersionNumberDesc(UUID policyId);

	/**
	 * Returns all versions for a policy in sequence order.
	 */
	List<PolicyVersion> findByPolicy_IdOrderByVersionNumberAsc(UUID policyId);

}
