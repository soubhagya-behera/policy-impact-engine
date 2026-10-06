package com.soubhagya.policyimpactengine.user.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 18-B — persistence for {@link GoogleCompletion} (see DECISIONS.md
 * ADR-037). Consumption is one atomic predicate update so exactly one
 * racing completion wins; every other presentation fails identically.
 */
public interface GoogleCompletionRepository extends JpaRepository<GoogleCompletion, UUID> {

	/**
	 * Atomically consumes exactly one live completion row: stamps the
	 * consumption time, but only when the row is still unconsumed and
	 * unexpired.
	 *
	 * @return 1 when this caller won the code, 0 otherwise
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE GoogleCompletion completion SET completion.consumedAt = :consumedAt"
			+ " WHERE completion.codeHash = :codeHash AND completion.consumedAt IS NULL"
			+ " AND completion.expiresAt > :now")
	int consumeCode(@Param("codeHash") String codeHash,
			@Param("consumedAt") Instant consumedAt,
			@Param("now") Instant now);

	Optional<GoogleCompletion> findByCodeHash(String codeHash);
}
