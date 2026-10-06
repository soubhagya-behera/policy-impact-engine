package com.soubhagya.policyimpactengine.user.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Phase 2P — minimal user identity (auth-deferred).
 *
 * <p>Phase 8A adds the nullable credential transition: {@code email}
 * (application-normalized trim + lowercase, unique) and
 * {@code passwordHash} (BCrypt, never a raw password). Both stay NULL
 * for pre-auth rows so the no-arg JPA constructor and
 * {@code UserService.createUser()} keep working; registration requires
 * both. DB-level NOT NULL tightening belongs to Phase 8B.
 * No roles, status, lockout, or token state (see DECISIONS.md ADR-017).
 */
@Entity
@Table(name = "app_user")
@Getter
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Phase 8A — login identifier, application-normalized (trim +
	 * lowercase), unique. NULL during the credential transition for
	 * pre-auth rows; required on the registration path.
	 */
	@Column(name = "email", nullable = true, updatable = false, length = 254)
	private String email;

	/**
	 * Phase 8A — BCrypt hash of the password. Never a raw password.
	 * NULL during the credential transition for pre-auth rows and for
	 * Google-only rows (Phase 18-B): null-hash rows cannot password-login.
	 * No public setter: written only through
	 * {@link #assignCredentials(String, String)} on registration.
	 */
	@Column(name = "password_hash", nullable = true, length = 255)
	private String passwordHash;

	/**
	 * Phase 18-B — Google OIDC subject (the {@code sub} claim), the stable
	 * provider identity anchor (see DECISIONS.md ADR-037). NULL for
	 * email/password-only and pre-auth rows. Email is a mutable hint and
	 * must never be the permanent OAuth identity. Insert-only like
	 * {@code email}: assigned once at provisioning and never mutated
	 * through dirty checking.
	 */
	@Column(name = "google_sub", nullable = true, updatable = false, length = 255)
	private String googleSub;

	public User() {
		// Required by JPA / creation.
	}

	/**
	 * Assigns credentials once, on registration. Email must already be
	 * normalized (trim + lowercase); the hash must already be BCrypt.
	 */
	public void assignCredentials(String normalizedEmail, String passwordHash) {
		if (normalizedEmail == null || normalizedEmail.isBlank()) {
			throw new IllegalArgumentException("Email must not be blank");
		}
		if (passwordHash == null || passwordHash.isBlank()) {
			throw new IllegalArgumentException("Password hash must not be blank");
		}
		if (this.email != null || this.passwordHash != null) {
			throw new IllegalStateException("Credentials are already assigned");
		}
		this.email = normalizedEmail;
		this.passwordHash = passwordHash;
	}

	/**
	 * Phase 18-B — provisions a Google-only account: normalized verified
	 * email plus the Google subject, with no password hash (such rows
	 * cannot password-login; login rejects null hashes uniformly).
	 */
	public void assignGoogleIdentity(String normalizedEmail, String googleSub) {
		if (normalizedEmail == null || normalizedEmail.isBlank()) {
			throw new IllegalArgumentException("Email must not be blank");
		}
		if (googleSub == null || googleSub.isBlank()) {
			throw new IllegalArgumentException("Google subject must not be blank");
		}
		if (this.email != null || this.passwordHash != null || this.googleSub != null) {
			throw new IllegalStateException("Identity is already assigned");
		}
		this.email = normalizedEmail;
		this.googleSub = googleSub;
	}
}
