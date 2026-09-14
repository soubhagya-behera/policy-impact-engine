package com.soubhagya.policyimpactengine.policy.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
	 * Creates a policy in its initial registration state.
	 */
	public Policy(String name, String url) {
		this.name = name;
		this.url = url;
		this.status = PolicyStatus.ACTIVE;
	}

}
