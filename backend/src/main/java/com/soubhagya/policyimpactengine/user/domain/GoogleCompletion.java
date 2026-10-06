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
 * Phase 18-B — one-time Google completion codes (see DECISIONS.md ADR-037).
 *
 * <p>Bridges the backend OAuth callback (a top-level navigation that must
 * never carry tokens) to the SPA callback page. Stores only the SHA-256
 * hex digest of an opaque code; the raw code travels exactly once inside
 * the server-side redirect to the frontend callback path. Each row is
 * consumed at most once by an atomic predicate update; expiry is absolute
 * (10 minutes). No raw code, token, or secret ever touches this table.
 */
@Entity
@Table(name = "auth_google_completion", uniqueConstraints = @UniqueConstraint(name = "uq_auth_google_completion_hash", columnNames = {
		"code_hash" }))
@Getter
public class GoogleCompletion {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	/**
	 * SHA-256 hex digest of the opaque completion code. Lowercase hex,
	 * exactly 64 characters; never the raw code.
	 */
	@Column(name = "code_hash", nullable = false, updatable = false, length = 64)
	private String codeHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at", nullable = true, updatable = true)
	private Instant consumedAt;

	protected GoogleCompletion() {
		// Required by JPA.
	}

	public GoogleCompletion(User user, String codeHash, Instant createdAt, Instant expiresAt) {
		if (user == null) {
			throw new IllegalArgumentException("User must not be null");
		}
		if (codeHash == null || codeHash.length() != 64) {
			throw new IllegalArgumentException("Code hash must be 64 lowercase hex characters");
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
		this.codeHash = codeHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.consumedAt = null;
	}
}
