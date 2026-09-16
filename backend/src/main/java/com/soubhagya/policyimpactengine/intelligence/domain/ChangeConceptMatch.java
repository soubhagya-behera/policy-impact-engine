package com.soubhagya.policyimpactengine.intelligence.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;

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
 * Immutable persisted deterministic match between a {@link PolicyChangeRecord}
 * and a {@link PrivacyConcept} with traceable evidence.
 *
 * <p>Append-only like {@link PolicyChangeRecord}: never edited or deleted.
 * No setters, no update/delete API. The evidence fragment must be an actual
 * substring of the source change text (oldText or newText).
 *
 * <p>The pair (change, concept) is unique: a change matches a concept at most
 * once per transition, even if multiple patterns or both old/new texts would
 * fire. Deterministic deduplication is enforced by the database unique
 * constraint.
 */
@Entity
@Table(name = "change_concept_match", uniqueConstraints = @UniqueConstraint(name = "uq_change_concept_match", columnNames = {
		"change_id", "concept_id" }))
@Getter
public class ChangeConceptMatch {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "change_id", nullable = false, updatable = false)
	private PolicyChangeRecord change;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "concept_id", nullable = false, updatable = false)
	private PrivacyConcept concept;

	@Column(name = "matched_fragment", nullable = false, updatable = false, columnDefinition = "TEXT")
	private String matchedFragment;

	@Column(name = "pattern_id", nullable = false, updatable = false, length = 128)
	private String patternId;

	@Column(name = "match_kind", nullable = false, updatable = false, length = 32)
	private String matchKind;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ChangeConceptMatch() {
		// Required by JPA.
	}

	/**
	 * Creates a new immutable match. All state is fixed at construction time.
	 */
	public ChangeConceptMatch(PolicyChangeRecord change, PrivacyConcept concept, String matchedFragment,
			String patternId, String matchKind) {
		if (change == null) {
			throw new IllegalArgumentException("Change must not be null");
		}
		if (concept == null) {
			throw new IllegalArgumentException("Concept must not be null");
		}
		if (matchedFragment == null || matchedFragment.isBlank()) {
			throw new IllegalArgumentException("Matched fragment must not be blank");
		}
		if (patternId == null || patternId.isBlank()) {
			throw new IllegalArgumentException("Pattern id must not be blank");
		}
		if (matchKind == null || matchKind.isBlank()) {
			throw new IllegalArgumentException("Match kind must not be blank");
		}
		this.change = change;
		this.concept = concept;
		this.matchedFragment = matchedFragment;
		this.patternId = patternId;
		this.matchKind = matchKind;
	}
}
