package com.soubhagya.policyimpactengine.user.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * Phase 14-A/2a — persisted SHA-256 hex digest of one opaque refresh token
 * (see DECISIONS.md ADR-029).
 *
 * <p>Stores only the digest ({@code tokenHash}); the raw token never
 * touches the database and no raw-token field exists here. Each row is one
 * issued token's validity window ({@code createdAt}/{@code expiresAt},
 * expiry strictly after creation) owned by a {@link User}.
 *
 * <p>Lifecycle mutability is explicitly sanctioned and narrow, mirroring
 * {@link com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt}:
 * the row is created live ({@code revokedAt} and
 * {@code replacedByTokenHash} both {@code NULL}) and transitions at most
 * once. {@link #consume} stamps both columns together for the atomic
 * rotation path; {@link #revoke} stamps only {@code revokedAt} for family
 * revocation and is idempotent so repeated sweeps converge. There are no
 * setters and no other mutation paths. Superseded rows are retained until
 * expiry so reuse stays detectable; purge removes expired rows only.
 */
@Entity
@Table(name = "auth_refresh_token", uniqueConstraints = @UniqueConstraint(name = "uq_auth_refresh_token_hash", columnNames = {
		"token_hash" }))
@Getter
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	/**
	 * SHA-256 hex digest of the opaque refresh token. Lowercase hex,
	 * exactly 64 characters; never the raw token.
	 */
	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at", nullable = true, updatable = true)
	private Instant revokedAt;

	/**
	 * Digest of the successor token installed by rotation, or
	 * {@code NULL} when this row was revoked without a successor
	 * (family revocation) or is still live.
	 */
	@Column(name = "replaced_by_token_hash", nullable = true, updatable = true, length = 64)
	private String replacedByTokenHash;

	protected RefreshToken() {
		// Required by JPA.
	}

	public RefreshToken(User user, String tokenHash, Instant createdAt, Instant expiresAt) {
		if (user == null) {
			throw new IllegalArgumentException("User must not be null");
		}
		if (!isHex64(tokenHash)) {
			throw new IllegalArgumentException("Token hash must be 64 lowercase hex characters");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("Created at must not be null");
		}
		if (expiresAt == null) {
			throw new IllegalArgumentException("Expires at must not be null");
		}
		if (!expiresAt.isAfter(createdAt)) {
			throw new IllegalArgumentException("Expires at must be after created at");
		}
		this.user = user;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.revokedAt = null;
		this.replacedByTokenHash = null;
	}

	/**
	 * Records the single sanctioned rotation transition: stamps the
	 * consumption time and the successor digest together. Rejects a
	 * second transition and any blank or malformed successor.
	 */
	public void consume(Instant consumedAt, String successorHash) {
		if (consumedAt == null) {
			throw new IllegalArgumentException("Consumed at must not be null");
		}
		if (!isHex64(successorHash)) {
			throw new IllegalArgumentException("Successor hash must be 64 lowercase hex characters");
		}
		if (this.revokedAt != null || this.replacedByTokenHash != null) {
			throw new IllegalStateException("Token is already consumed or revoked");
		}
		if (consumedAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("Consumed at must not be before created at");
		}
		this.revokedAt = consumedAt;
		this.replacedByTokenHash = successorHash;
	}

	/**
	 * Records family revocation: stamps {@code revokedAt} without a
	 * successor link. Idempotent: a second call is a no-op so repeated
	 * sweeps converge; the successor link of a consumed row is never
	 * cleared.
	 */
	public void revoke(Instant revokedAt) {
		if (revokedAt == null) {
			throw new IllegalArgumentException("Revoked at must not be null");
		}
		if (this.revokedAt != null) {
			return;
		}
		if (revokedAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("Revoked at must not be before created at");
		}
		this.revokedAt = revokedAt;
	}

	private static boolean isHex64(String value) {
		if (value == null || value.length() != 64) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
				return false;
			}
		}
		return true;
	}
}
