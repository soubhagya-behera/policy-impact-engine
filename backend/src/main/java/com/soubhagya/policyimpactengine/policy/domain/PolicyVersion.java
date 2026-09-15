package com.soubhagya.policyimpactengine.policy.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

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
 * An immutable observed version of a {@link Policy}.
 *
 * <p>Append-only: versions are never edited or deleted after creation;
 * corrections produce new versions. There are deliberately no setters and no
 * update/delete APIs on this entity or in the versioning service.
 *
 * <p>Each version records the canonical normalized content, its SHA-256 hash
 * (computed by the caller over the normalized content, never inside the
 * versioning slice), and a monotonically increasing version number scoped to
 * the parent policy. Version 1 is the first observed content for a policy.
 */
@Entity
@Table(name = "policy_version", uniqueConstraints = @UniqueConstraint(name = "uq_policy_version_policy_number", columnNames = {
		"policy_id", "version_number" }))
@Getter
public class PolicyVersion {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "policy_id", nullable = false, updatable = false)
	private Policy policy;

	/** 1-based sequence scoped to the parent policy. Enforced unique per policy in the database. */
	@Column(name = "version_number", nullable = false, updatable = false)
	private int versionNumber;

	/** Lowercase hex SHA-256 of the normalized content, supplied by the caller. */
	@Column(name = "content_hash", nullable = false, updatable = false, length = 64)
	private String contentHash;

	/** Canonical normalized content observed at this version. */
	@Column(name = "normalized_content", nullable = false, updatable = false, columnDefinition = "TEXT")
	private String normalizedContent;

	@CreationTimestamp
	@Column(name = "observed_at", nullable = false, updatable = false)
	private Instant observedAt;

	protected PolicyVersion() {
		// Required by JPA.
	}

	/**
	 * Creates a new immutable version. No setters are exposed; all state is
	 * fixed at construction time.
	 */
	public PolicyVersion(Policy policy, int versionNumber, String normalizedContent, String contentHash) {
		if (policy == null) {
			throw new IllegalArgumentException("Policy must not be null");
		}
		if (versionNumber < 1) {
			throw new IllegalArgumentException("Version number must be >= 1");
		}
		if (normalizedContent == null) {
			throw new IllegalArgumentException("Normalized content must not be null");
		}
		if (contentHash == null || contentHash.isBlank()) {
			throw new IllegalArgumentException("Content hash must not be blank");
		}
		this.policy = policy;
		this.versionNumber = versionNumber;
		this.normalizedContent = normalizedContent;
		this.contentHash = contentHash;
	}

}
