package com.soubhagya.policyimpactengine.user.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 14-A/2a — persistence for {@link RefreshToken} (see DECISIONS.md
 * ADR-029).
 *
 * <p>Persistence operations only; rotation/reuse orchestration belongs to
 * the later service slice. Successor rows persist through the inherited
 * {@code save} inside the refresh transaction that consumes the
 * predecessor. No update/delete paths exist beyond the three sanctioned
 * bulk operations below: the atomic consume, the family revocation, and
 * the expired-row purge.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	/**
	 * Resolves the single row for the presented digest, if any. Unknown,
	 * expired, revoked, and reused digests all resolve through this same
	 * lookup; the caller distinguishes them without any oracle detail.
	 */
	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Returns the user's live tokens (unrevoked and unexpired at
	 * {@code now}) backing family revocation reads.
	 */
	List<RefreshToken> findByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(UUID userId, Instant now);

	/**
	 * Atomically consumes exactly one live token: stamps the consumption
	 * time and the successor digest together, but only when the row is
	 * still unrevoked, successor-free, and unexpired. The persistence
	 * context is cleared so callers re-read the freshly consumed row.
	 *
	 * @return 1 when this caller won the rotation, 0 when the digest is
	 *         unknown, expired, already consumed, or revoked (the caller
	 *         must treat every 0 identically)
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE RefreshToken token SET token.revokedAt = :consumedAt,"
			+ " token.replacedByTokenHash = :successorHash"
			+ " WHERE token.tokenHash = :tokenHash AND token.revokedAt IS NULL"
			+ " AND token.replacedByTokenHash IS NULL AND token.expiresAt > :now")
	int consumeToken(@Param("tokenHash") String tokenHash,
			@Param("successorHash") String successorHash,
			@Param("consumedAt") Instant consumedAt,
			@Param("now") Instant now);

	/**
	 * Revokes every live token of the user (unrevoked and unexpired at
	 * {@code now}) for reuse containment. Already-revoked and expired
	 * rows match nothing, so detection linkage on superseded rows is
	 * preserved.
	 *
	 * @return the number of rows revoked by this call
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE RefreshToken token SET token.revokedAt = :revokedAt"
			+ " WHERE token.user.id = :userId AND token.revokedAt IS NULL"
			+ " AND token.expiresAt > :now")
	int revokeLiveTokensForUser(@Param("userId") UUID userId,
			@Param("revokedAt") Instant revokedAt,
			@Param("now") Instant now);

	/**
	 * Purges expired rows (detection window over) in a bounded batch:
	 * only rows with {@code expiresAt} at or before {@code cutoff} ever
	 * match, so live and unexpired revoked rows are never touched.
	 *
	 * @return the number of rows purged by this call
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("DELETE FROM RefreshToken token WHERE token.expiresAt <= :cutoff")
	int purgeExpired(@Param("cutoff") Instant cutoff);
}
