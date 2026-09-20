package com.soubhagya.policyimpactengine.user.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 2P — minimal persistence for {@link User}.
 *
 * <p>Phase 8A adds the normalized-email lookup backing duplicate
 * registration detection.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);
}
