package com.soubhagya.policyimpactengine.user.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 2P — minimal persistence for {@link User}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {
}
