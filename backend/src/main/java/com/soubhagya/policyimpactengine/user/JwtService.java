package com.soubhagya.policyimpactengine.user;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Phase 8B — HS256 access-token issuance and validation (see
 * DECISIONS.md ADR-018).
 *
 * <p>Claims are exactly {@code sub} (application User UUID string),
 * {@code iat}, and {@code exp}. Validation verifies signature,
 * algorithm, expiration, and a UUID-parseable subject; every failure
 * mode collapses to {@link JwtInvalidException} with no reason detail.
 * The injected {@link Clock} pins time in tests; production reuses the
 * shared system-UTC clock bean.
 */
@Service
public class JwtService {

	static final Duration DEFAULT_ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
	static final int MIN_SECRET_UTF8_BYTES = 32;

	private final byte[] secret;
	private final Duration accessTokenTtl;
	private final Clock clock;

	public JwtService(JwtProperties properties, Clock clock) {
		if (properties == null) {
			throw new IllegalArgumentException("JWT properties must not be null");
		}
		if (clock == null) {
			throw new IllegalArgumentException("Clock must not be null");
		}
		if (properties.secret() == null || properties.secret().isBlank()) {
			throw new IllegalStateException("security.jwt.secret must be configured");
		}
		byte[] key = properties.secret().getBytes(StandardCharsets.UTF_8);
		if (key.length < MIN_SECRET_UTF8_BYTES) {
			throw new IllegalStateException("security.jwt.secret must be at least 32 UTF-8 bytes");
		}
		this.secret = key;
		this.accessTokenTtl = properties.accessTokenTtl() != null
				? properties.accessTokenTtl()
				: DEFAULT_ACCESS_TOKEN_TTL;
		this.clock = clock;
	}

	/**
	 * Issues a signed access token for the given user.
	 */
	public String issueAccessToken(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		Date now = Date.from(clock.instant());
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.issueTime(now)
				.expirationTime(Date.from(now.toInstant().plus(accessTokenTtl)))
				.build();
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			jwt.sign(new MACSigner(secret));
			return jwt.serialize();
		}
		catch (JOSEException failed) {
			throw new IllegalStateException("Failed to issue access token", failed);
		}
	}

	/**
	 * Validates the token and returns the subject User UUID.
	 *
	 * @throws JwtInvalidException for every failure mode
	 */
	public UUID parseUserId(String token) {
		if (token == null || token.isBlank()) {
			throw new JwtInvalidException("Invalid token");
		}
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
				throw new JwtInvalidException("Invalid token");
			}
			JWSVerifier verifier = new MACVerifier(secret);
			if (!jwt.verify(verifier)) {
				throw new JwtInvalidException("Invalid token");
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			Date expiresAt = claims.getExpirationTime();
			if (expiresAt == null || !expiresAt.after(Date.from(clock.instant()))) {
				throw new JwtInvalidException("Invalid token");
			}
			String subject = claims.getSubject();
			if (subject == null || subject.isBlank()) {
				throw new JwtInvalidException("Invalid token");
			}
			try {
				return UUID.fromString(subject);
			}
			catch (IllegalArgumentException malformed) {
				throw new JwtInvalidException("Invalid token");
			}
		}
		catch (ParseException | JOSEException failed) {
			throw new JwtInvalidException("Invalid token");
		}
	}

	/**
	 * Token lifetime in seconds, surfaced as login response metadata.
	 */
	public long accessTokenExpiresInSeconds() {
		return accessTokenTtl.toSeconds();
	}
}
