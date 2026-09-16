package com.soubhagya.policyimpactengine.intelligence.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * Immutable privacy concept vocabulary entry.
 *
 * <p>Database-backed, not an enum: new concepts can be added via Flyway
 * migrations without code changes. Append-only like {@link com.soubhagya.policyimpactengine.policy.domain.PolicyVersion}.
 * No setters, no update or delete API.
 *
 * <p>Owns its row's invariants: code unique, non-blank label/description,
 * non-negative weight, sensitivity in [0,5].
 */
@Entity
@Table(name = "privacy_concept", uniqueConstraints = @UniqueConstraint(name = "uq_privacy_concept_code", columnNames = "code"))
@Getter
public class PrivacyConcept {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(nullable = false, updatable = false, length = 64)
	private String code;

	@Column(nullable = false, updatable = false, length = 255)
	private String label;

	@Column(nullable = false, updatable = false, columnDefinition = "TEXT")
	private String description;

	@Column(name = "default_weight", nullable = false, updatable = false)
	private int defaultWeight;

	@Column(name = "default_sensitivity", nullable = false, updatable = false)
	private int defaultSensitivity;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected PrivacyConcept() {
		// Required by JPA.
	}

	/**
	 * Creates a new immutable concept. All state is fixed at construction time.
	 */
	public PrivacyConcept(String code, String label, String description, int defaultWeight,
			int defaultSensitivity) {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("Code must not be blank");
		}
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("Label must not be blank");
		}
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("Description must not be blank");
		}
		if (defaultWeight < 0) {
			throw new IllegalArgumentException("Default weight must be >= 0");
		}
		if (defaultSensitivity < 0 || defaultSensitivity > 5) {
			throw new IllegalArgumentException("Default sensitivity must be between 0 and 5");
		}
		this.code = code.trim();
		this.label = label.trim();
		this.description = description.trim();
		this.defaultWeight = defaultWeight;
		this.defaultSensitivity = defaultSensitivity;
	}
}
