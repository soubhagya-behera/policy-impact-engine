package com.soubhagya.policyimpactengine.user.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 2P — minimal persistence for {@link User}.
 *
 * <p>Phase 8A adds the normalized-email lookup backing duplicate
 * registration detection.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	/**
	 * Phase 18-B — resolves the local account anchored to a Google OIDC
	 * subject (see DECISIONS.md ADR-037). The subject is the stable
	 * provider identity; email is only a mutable hint.
	 */
	Optional<User> findByGoogleSub(String googleSub);

	/**
	 * Phase 18-B — applies a verified Google email change on the owning
	 * account only (see DECISIONS.md ADR-037). Callers must verify the
	 * target address is collision-free first; the unique email index is
	 * the final guard.
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE User user SET user.email = :email WHERE user.id = :userId")
	int updateEmail(@Param("userId") UUID userId, @Param("email") String email);
}
