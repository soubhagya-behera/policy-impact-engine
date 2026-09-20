package com.soubhagya.policyimpactengine.policy.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.soubhagya.policyimpactengine.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A policy registered for tracking. Phase 1 covers registration metadata only;
 * versioning, sections, and change analysis are added by later phases.
 */
@Entity
@Table(name = "policy")
@Getter
@Setter
public class Policy {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	/** Human-readable name of the policy, e.g. "Acme Privacy Policy". */
	@Column(nullable = false)
	private String name;

	/** Absolute https URL of the policy document. */
	@Column(nullable = false, length = 2048)
	private String url;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private PolicyStatus status;

	/**
	 * Next time this policy becomes eligible for a scheduled observation.
	 * Advanced by the monitoring scheduler (Phase 2T) by the configured
	 * interval after every completed check. New policies start due
	 * immediately; tests may override via the setter.
	 */
	@Column(name = "next_check_at", nullable = false)
	private Instant nextCheckAt;

	/**
	 * Phase 10B-1 — single owner of this policy (nullable transition).
	 *
	 * <p>Exactly one owner per policy; NULL means unowned. An unowned
	 * policy is observed normally but stays silent: no assessment, no
	 * recommendation, no notification. There is no policy_user,
	 * subscription, or watch table and no many-to-many relationship.
	 */
	@ManyToOne(fetch = FetchType.EAGER, optional = true)
	@JoinColumn(name = "owner_id", nullable = true)
	private User owner;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

	protected Policy() {
		// Required by JPA.
	}

	/**
	 * Creates a policy in its initial registration state, immediately
	 * eligible for its first scheduled check.
	 */
	public Policy(String name, String url) {
		this.name = name;
		this.url = url;
		this.status = PolicyStatus.ACTIVE;
		this.nextCheckAt = Instant.now();
	}

}
