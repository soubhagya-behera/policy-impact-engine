package com.soubhagya.policyimpactengine.user;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;

/**
 * Phase 15-A/2 — logout and session revocation (see DECISIONS.md
 * ADR-031).
 *
 * <p>Single-session logout revokes exactly the presented row when it is
 * still live (unrevoked + unexpired) through the {@code revokeToken}
 * predicate in one short transaction. Unknown, expired, revoked, and
 * superseded digests all resolve to an idempotent no-op with no user
 * id — never a family revocation, never an exception carrying token
 * state. Presenting a superseded row must not destroy the victim's
 * live family through this path; reuse containment stays exclusive to
 * refresh rotation (ADR-029 §5).
 *
 * <p>Logout-all revokes every live row of the explicit user id through
 * the existing bulk family predicate; zero live rows is a successful
 * no-op. Only the SHA-256 hex digest is ever persisted or compared;
 * the raw token is hashed in memory, never logged, and never stored.
 * All time comes from the injected {@link Clock}.
 *
 * <p>Concurrency across threads and instances uses database predicates
 * only, never Java synchronization: concurrent same-token logouts
 * converge with exactly one revoker while every caller observes the
 * same idempotent outcome.
 */
@Service
public class AuthLogoutService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;

	public AuthLogoutService(RefreshTokenRepository refreshTokenRepository,
			PlatformTransactionManager transactionManager, Clock clock) {
		if (refreshTokenRepository == null) {
			throw new IllegalArgumentException("RefreshTokenRepository must not be null");
		}
		if (transactionManager == null) {
			throw new IllegalArgumentException("Transaction manager must not be null");
		}
		if (clock == null) {
			throw new IllegalArgumentException("Clock must not be null");
		}
		this.refreshTokenRepository = refreshTokenRepository;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Revokes the presented refresh session when it is still live.
	 * Every non-live state yields the same idempotent no-op.
	 *
	 * @param presentedRawToken opaque raw token from the request body
	 * @return revoked outcome with the owning user id, or a no-op
	 */
	public LogoutResult logoutSingle(String presentedRawToken) {
		if (presentedRawToken == null || presentedRawToken.isBlank()) {
			throw new IllegalArgumentException("Refresh token must not be blank");
		}
		String presentedHash = AuthRefreshService.sha256Hex(presentedRawToken);
		Instant now = clock.instant();
		UUID owner = writeTransaction.execute(status -> {
			var found = refreshTokenRepository.findByTokenHash(presentedHash);
			if (found.isEmpty()) {
				return null;
			}
			RefreshToken row = found.get();
			if (!row.getExpiresAt().isAfter(now)) {
				return null;
			}
			if (row.getRevokedAt() != null) {
				return null;
			}
			int revoked = refreshTokenRepository.revokeToken(presentedHash, now, now);
			if (revoked != 1) {
				return null;
			}
			return row.getUser().getId();
		});
		return owner == null ? LogoutResult.noop() : LogoutResult.revoked(owner);
	}

	/**
	 * Revokes every live refresh session of the user.
	 *
	 * @param userId explicit owning user id from the authenticated principal
	 * @return revoked outcome, or a no-op when zero rows were live
	 */
	public LogoutResult logoutAll(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		Instant now = clock.instant();
		int revoked = writeTransaction.execute(status ->
				refreshTokenRepository.revokeLiveTokensForUser(userId, now, now));
		return revoked > 0 ? LogoutResult.revoked(userId) : LogoutResult.noop();
	}
}
