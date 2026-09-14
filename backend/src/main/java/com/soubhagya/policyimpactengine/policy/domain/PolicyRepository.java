package com.soubhagya.policyimpactengine.policy.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Policy}. Phase 1 needs no custom queries.
 */
public interface PolicyRepository extends JpaRepository<Policy, UUID> {

}
