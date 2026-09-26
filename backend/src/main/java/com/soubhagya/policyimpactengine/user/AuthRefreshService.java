package com.soubhagya.policyimpactengine.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 14-A/2b — refresh-token issuance, rotation, reuse containment, and
 * expired-row purge orchestration (see DECISIONS.md ADR-029).
 *
 * <p>Tokens are 256-bit opaque values from {@link SecureRandom},
 * base64url-encoded without padding. Only the lowercase SHA-256 hex
 * digest is ever persisted; the raw token is returned to the caller
 * exactly once, never logged, and never stored. Expiry is absolute:
 * every row lives {@code issuedAt + refresh TTL} where the TTL is the
 * validated {@code security.jwt.refresh-token-ttl} owned by
 * {@link JwtService} — no sliding renewal, rotation never extends it.
 * All time comes from the injected {@link Clock}; there is no
 * {@code Instant.now()} in this class.
 *
 * <p>Rotation runs in one short write transaction: hash the presented
 * token, resolve the row, then consume it with the existing predicate
 * update and insert the successor atomically. Unknown, expired,
 * revoked, reused, and concurrent-loser presentations all collapse to
 * the same {@link InvalidRefreshTokenException} with one fixed message.
 * Presenting a superseded row (one carrying a successor link) revokes
 * every live row of the owning user — derived from that row — before
 * failing uniformly; expired rows never trigger family revocation
 * because the expiry check runs first.
 *
 * <p>Concurrency across threads and instances uses database predicate
 * updates only, never Java synchronization: the consume update matches
 * at most the still-live row, so exactly one racing rotation wins and
 * every loser fails uniformly without further work.
 */
@Service
public class AuthRefreshService {

	static final String INVALID_REFRESH_TOKEN_MESSAGE = "Invalid refresh token";
	static final int REFRESH_TOKEN_ENTROPY_BYTES = 32;

	private final RefreshTokenRepository refreshTokenRepository;
	private final UserRepository userRepository;
	private final JwtService jwtService;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;
	private final SecureRandom secureRandom;

	@Autowired
	public AuthRefreshService(RefreshTokenRepository refreshTokenRepository,
			UserRepository userRepository, JwtService jwtService,
			PlatformTransactionManager transactionManager, Clock clock) {
		this(refreshTokenRepository, userRepository, jwtService, transactionManager, clock,
				new SecureRandom());
	}

	public AuthRefreshService(RefreshTokenRepository refreshTokenRepository,
			UserRepository userRepository, JwtService jwtService,
			PlatformTransactionManager transactionManager, Clock clock,
			SecureRandom secureRandom) {
		if (refreshTokenRepository == null) {
			throw new IllegalArgumentException("RefreshTokenRepository must not be null");
		}
		if (userRepository == null) {
			throw new IllegalArgumentException("UserRepository must not be null");
		}
		if (jwtService == null) {
			throw new IllegalArgumentException("JwtService must not be null");
		}
		if (transactionManager == null) {
			throw new IllegalArgumentException("Transaction manager must not be null");
		}
		if (clock == null) {
			throw new IllegalArgumentException("Clock must not be null");
		}
		if (secureRandom == null) {
			throw new IllegalArgumentException("SecureRandom must not be null");
		}
		this.refreshTokenRepository = refreshTokenRepository;
		this.userRepository = userRepository;
		this.jwtService = jwtService;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.secureRandom = secureRandom;
	}

	/**
	 * Issues the first refresh-token row for the user, persisting only
	 * the digest. Preparation for the later login integration; the login
	 * service itself is untouched by this slice.
	 */
	public RefreshResult issueRefreshToken(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		Instant issuedAt = clock.instant();
		long lifetimeSeconds = jwtService.refreshTokenExpiresInSeconds();
		Instant expiresAt = issuedAt.plusSeconds(lifetimeSeconds);
		String rawToken = generateRawToken(secureRandom);
		String tokenHash = sha256Hex(rawToken);
		writeTransaction.execute(status -> refreshTokenRepository
				.saveAndFlush(new RefreshToken(user, tokenHash, issuedAt, expiresAt)));
		return new RefreshResult(user.getId(), rawToken, expiresAt, lifetimeSeconds);
	}

	/**
	 * Rotates the presented raw token: atomically consumes its row and
	 * inserts the successor, returning the new raw token exactly once.
	 * Every failure mode yields the same exception and message.
	 *
	 * <p>Failure is signalled by returning {@code null} from the
	 * transaction rather than throwing out of it: the reuse path writes
	 * the family revocation, which must commit instead of being rolled
	 * back with the rotation failure. The uniform exception is raised
	 * after the transaction completes.
	 */
	public RefreshResult rotate(String presentedRawToken) {
		if (presentedRawToken == null || presentedRawToken.isBlank()) {
			throw invalid();
		}
		String presentedHash = sha256Hex(presentedRawToken);
		Instant now = clock.instant();
		long lifetimeSeconds = jwtService.refreshTokenExpiresInSeconds();
		RefreshResult rotated = writeTransaction.execute(status -> {
			var found = refreshTokenRepository.findByTokenHash(presentedHash);
			if (found.isEmpty()) {
				return null;
			}
			RefreshToken row = found.get();
			if (!row.getExpiresAt().isAfter(now)) {
				return null;
			}
			if (row.getRevokedAt() != null) {
				if (row.getReplacedByTokenHash() != null) {
					refreshTokenRepository.revokeLiveTokensForUser(
							row.getUser().getId(), now, now);
				}
				return null;
			}
			String successorRaw = generateRawToken(secureRandom);
			String successorHash = sha256Hex(successorRaw);
			Instant expiresAt = now.plusSeconds(lifetimeSeconds);
			int consumed = refreshTokenRepository.consumeToken(
					presentedHash, successorHash, now, now);
			if (consumed != 1) {
				return null;
			}
			refreshTokenRepository.saveAndFlush(
					new RefreshToken(row.getUser(), successorHash, now, expiresAt));
			return new RefreshResult(row.getUser().getId(), successorRaw, expiresAt,
					lifetimeSeconds);
		});
		if (rotated == null) {
			throw invalid();
		}
		return rotated;
	}

	/**
	 * Scheduled expired-row purge. Each run deletes only rows already
	 * expired at the tick start; live and unexpired revoked rows are
	 * never touched. Detection linkage on superseded rows survives until
	 * their own expiry, per ADR-029.
	 */
	@Scheduled(fixedDelayString = "${auth.refresh-token.purge-interval:PT24H}")
	public void purgeExpiredTokensScheduled() {
		purgeExpiredTokens(clock.instant());
	}

	/**
	 * Purges the rows expired at {@code now}.
	 *
	 * @return how many rows this run purged
	 */
	public int purgeExpiredTokens(Instant now) {
		if (now == null) {
			throw new IllegalArgumentException("Purge time must not be null");
		}
		return refreshTokenRepository.purgeExpired(now);
	}

	/**
	 * Generates one 256-bit opaque token, base64url-encoded without
	 * padding (43 characters). The raw value leaves this method only
	 * toward the single caller receiving it.
	 */
	static String generateRawToken(SecureRandom random) {
		if (random == null) {
			throw new IllegalArgumentException("SecureRandom must not be null");
		}
		byte[] entropy = new byte[REFRESH_TOKEN_ENTROPY_BYTES];
		random.nextBytes(entropy);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
	}

	/**
	 * Digests the raw token to the lowercase SHA-256 hex persisted in
	 * place of the token itself.
	 */
	static String sha256Hex(String rawToken) {
		if (rawToken == null) {
			throw new IllegalArgumentException("Raw token must not be null");
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
	}

	private static InvalidRefreshTokenException invalid() {
		return new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE);
	}
}
