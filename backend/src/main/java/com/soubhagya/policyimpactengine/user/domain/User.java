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
 * <p>Immutable except for timestamps: only id+created_at/updated_at.
 * No email/password/role — deferred to authentication phase.
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

	public User() {
		// Required by JPA / creation.
	}
}
