package com.soubhagya.policyimpactengine.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.user.domain.GoogleCompletion;
import com.soubhagya.policyimpactengine.user.domain.GoogleCompletionRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 18-B — one-time Google completion codes (see DECISIONS.md ADR-037).
 *
 * <p>Codes are 256-bit opaque values (base64url, no padding); only the
 * lowercase SHA-256 hex digest is persisted. Absolute 10-minute TTL, no
 * renewal. Completion consumes the code row atomically, then issues the
 * refresh token for the code owner plus the matching access token, all in
 * one short transaction. Unknown, expired, consumed, and concurrent-loser
 * presentations all collapse to {@link InvalidGoogleIdentityException}
 * with one fixed message. Never logs raw codes or tokens.
 */
@Service
public class GoogleCompletionService {

	static final Duration COMPLETION_TTL = Duration.ofMinutes(10);

	private final GoogleCompletionRepository completionRepository;
	private final UserRepository userRepository;
	private final AuthRefreshService refreshService;
	private final JwtService jwtService;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;
	private final SecureRandom secureRandom;

	@Autowired
	public GoogleCompletionService(GoogleCompletionRepository completionRepository,
			UserRepository userRepository, AuthRefreshService refreshService,
			JwtService jwtService, PlatformTransactionManager transactionManager, Clock clock) {
		this(completionRepository, userRepository, refreshService, jwtService,
				transactionManager, clock, new SecureRandom());
	}

	GoogleCompletionService(GoogleCompletionRepository completionRepository,
			UserRepository userRepository, AuthRefreshService refreshService,
			JwtService jwtService, PlatformTransactionManager transactionManager, Clock clock,
			SecureRandom secureRandom) {
		if (completionRepository == null) {
			throw new IllegalArgumentException("CompletionRepository must not be null");
		}
		if (userRepository == null) {
			throw new IllegalArgumentException("UserRepository must not be null");
		}
		if (refreshService == null) {
			throw new IllegalArgumentException("RefreshService must not be null");
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
		this.completionRepository = completionRepository;
		this.userRepository = userRepository;
		this.refreshService = refreshService;
		this.jwtService = jwtService;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.secureRandom = secureRandom;
	}

	/** Mints one completion code, persisting only its digest. */
	public String issueCode(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(COMPLETION_TTL);
		byte[] entropy = new byte[32];
		secureRandom.nextBytes(entropy);
		String rawCode = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
		String codeHash = sha256Hex(rawCode);
		writeTransaction.execute(status -> completionRepository
				.saveAndFlush(new GoogleCompletion(user, codeHash, issuedAt, expiresAt)));
		return rawCode;
	}

	/** Exchanges a code for the owner's pair, consuming it atomically. */
	public LoginResult complete(String presentedCode) {
		if (presentedCode == null || presentedCode.isBlank()) {
			throw new InvalidGoogleIdentityException(AuthGoogleService.INVALID_IDENTITY_MESSAGE);
		}
		String presentedHash = sha256Hex(presentedCode);
		Instant now = clock.instant();
		LoginResult completed = writeTransaction.execute(status -> {
			int consumed = completionRepository.consumeCode(presentedHash, now, now);
			if (consumed != 1) {
				return null;
			}
			UUID ownerId = completionRepository.findByCodeHash(presentedHash)
					.map(row -> row.getUser().getId()).orElse(null);
			if (ownerId == null) {
				return null;
			}
			RefreshResult refresh;
			try {
				refresh = refreshService.issueRefreshToken(ownerId);
			}
			catch (RuntimeException issuanceFailure) {
				status.setRollbackOnly();
				return null;
			}
			return new LoginResult(ownerId, jwtService.issueAccessToken(ownerId),
					jwtService.accessTokenExpiresInSeconds(),
					refresh.refreshToken(), refresh.expiresInSeconds());
		});
		if (completed == null) {
			throw new InvalidGoogleIdentityException(AuthGoogleService.INVALID_IDENTITY_MESSAGE);
		}
		return completed;
	}

	static String sha256Hex(String rawCode) {
		if (rawCode == null) {
			throw new IllegalArgumentException("Raw code must not be null");
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(rawCode.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
	}
}


