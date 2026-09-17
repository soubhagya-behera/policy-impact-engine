package com.soubhagya.policyimpactengine.user.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;

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
 * Phase 2P — mutable per-user per-concept sensitivity preference.
 *
 * <p>Unlike version/history entities, this row is updateable: the user may
 * change sensitivity 0..5 or delete the row to revert to
 * PrivacyConcept.default_sensitivity fallback. 0 is explicit, absent row is fallback.
 */
@Entity
@Table(name = "user_privacy_preference", uniqueConstraints = @UniqueConstraint(name = "uq_user_privacy_preference", columnNames = {
		"user_id", "concept_id" }))
@Getter
public class UserPrivacyPreference {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "concept_id", nullable = false, updatable = false)
	private PrivacyConcept concept;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(nullable = false)
	private int sensitivity;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserPrivacyPreference() {
		// Required by JPA.
	}

	public UserPrivacyPreference(User user, PrivacyConcept concept, int sensitivity) {
		if (user == null) {
			throw new IllegalArgumentException("User must not be null");
		}
		if (concept == null) {
			throw new IllegalArgumentException("Concept must not be null");
		}
		validateSensitivity(sensitivity);
		this.user = user;
		this.concept = concept;
		this.sensitivity = sensitivity;
	}

	/**
	 * Domain method for explicit sensitivity update.
	 */
	public void updateSensitivity(int sensitivity) {
		validateSensitivity(sensitivity);
		this.sensitivity = sensitivity;
	}

	private static void validateSensitivity(int sensitivity) {
		if (sensitivity < 0 || sensitivity > 5) {
			throw new IllegalArgumentException("Sensitivity must be between 0 and 5");
		}
	}
}
