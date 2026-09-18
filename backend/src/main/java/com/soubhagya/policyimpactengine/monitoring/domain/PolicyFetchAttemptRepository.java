package com.soubhagya.policyimpactengine.monitoring.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 2S — persistence for {@link PolicyFetchAttempt}.
 *
 * <p>Only the minimum operations required by Phase 2S are exposed:
 * standard save support from {@link JpaRepository} (used inside the short
 * attempt transactions owned by the attempt service) and deterministic
 * newest-first retrieval of one policy's attempt history. No update,
 * custom delete, scheduling, claiming, or retry logic lives here.
 */
public interface PolicyFetchAttemptRepository extends JpaRepository<PolicyFetchAttempt, UUID> {

	/**
	 * Returns the recorded attempts for the given policy, newest first.
	 */
	List<PolicyFetchAttempt> findByPolicy_IdOrderByStartedAtDesc(UUID policyId);

}
