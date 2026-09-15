package com.soubhagya.policyimpactengine.diff.domain;

import java.util.UUID;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;

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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * An immutable persisted textual change for one policy version transition.
 *
 * <p>One row corresponds to one {@code PolicyChange} of the
 * {@code PolicyDiffResult} computed between two persisted versions:
 * previous version N-1 to new version N. The owning policy is answered
 * through either version ({@code newVersion.getPolicy()}), so no separate
 * {@code policy_id} column is stored.
 *
 * <p>Append-only, like {@link PolicyVersion}: records are never edited or
 * deleted after creation; corrections arrive as new versions with new
 * change records. There are deliberately no setters and no update/delete
 * APIs. {@code changeOrder} is the zero-based position in the deterministic
 * document-ordered diff; the database unique constraint on
 * {@code (new_version_id, change_order)} enforces one row per position.
 */
@Entity
@Table(name = "policy_change", uniqueConstraints = @UniqueConstraint(name = "uq_policy_change_new_version_order", columnNames = {
		"new_version_id", "change_order" }))
@Getter
public class PolicyChangeRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "previous_version_id", nullable = false, updatable = false)
	private PolicyVersion previousVersion;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "new_version_id", nullable = false, updatable = false)
	private PolicyVersion newVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "change_type", nullable = false, updatable = false, length = 16)
	private PolicyChangeType changeType;

	@Column(name = "old_text", updatable = false, columnDefinition = "TEXT")
	private String oldText;

	@Column(name = "new_text", updatable = false, columnDefinition = "TEXT")
	private String newText;

	/** Zero-based position in the deterministic document-ordered diff. */
	@Column(name = "change_order", nullable = false, updatable = false)
	private int changeOrder;

	protected PolicyChangeRecord() {
		// Required by JPA.
	}

	/**
	 * Creates a new immutable change record. All state is fixed at
	 * construction time; null-shape rules mirror the {@code PolicyChange}
	 * value ({@code ADDED} has null old text, {@code REMOVED} has null new
	 * text, {@code MODIFIED} has both and they differ).
	 */
	public PolicyChangeRecord(
			PolicyVersion previousVersion,
			PolicyVersion newVersion,
			PolicyChangeType changeType,
			String oldText,
			String newText,
			int changeOrder) {
		if (previousVersion == null) {
			throw new IllegalArgumentException("Previous version must not be null");
		}
		if (newVersion == null) {
			throw new IllegalArgumentException("New version must not be null");
		}
		if (changeType == null) {
			throw new IllegalArgumentException("Change type must not be null");
		}
		if (changeOrder < 0) {
			throw new IllegalArgumentException("Change order must be >= 0");
		}
		switch (changeType) {
			case ADDED -> {
				if (oldText != null) {
					throw new IllegalArgumentException("ADDED change must have null oldText");
				}
				if (newText == null) {
					throw new IllegalArgumentException("ADDED change must have non-null newText");
				}
			}
			case REMOVED -> {
				if (oldText == null) {
					throw new IllegalArgumentException("REMOVED change must have non-null oldText");
				}
				if (newText != null) {
					throw new IllegalArgumentException("REMOVED change must have null newText");
				}
			}
			case MODIFIED -> {
				if (oldText == null || newText == null) {
					throw new IllegalArgumentException("MODIFIED change must have non-null oldText and newText");
				}
				if (oldText.equals(newText)) {
					throw new IllegalArgumentException("MODIFIED change must have differing oldText and newText");
				}
			}
		}
		this.previousVersion = previousVersion;
		this.newVersion = newVersion;
		this.changeType = changeType;
		this.oldText = oldText;
		this.newText = newText;
		this.changeOrder = changeOrder;
	}

}
