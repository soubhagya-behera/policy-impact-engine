package com.soubhagya.policyimpactengine.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

/**
 * Phase 10A — persistence for {@link Notification}.
 *
 * <p>User-scoped queries only; no unrestricted listing. Ownership is
 * derived through the assessment (Notification → ImpactAssessment → User),
 * so callers first resolve the user's assessment (or the notification's
 * owning user through it) and then read here. The feed order is newest
 * first by creation time.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	/**
	 * Returns the single notification for the given assessment, if one was
	 * emitted. Backs idempotent emission: repeats and lost-insert races
	 * re-read the winner through the UNIQUE(assessment_id) index.
	 */
	Optional<Notification> findByAssessment_Id(UUID assessmentId);

	/**
	 * Returns the user's notifications, newest first.
	 */
	List<Notification> findByAssessment_User_IdOrderByCreatedAtDesc(UUID userId);

	/**
	 * Returns the user's unread notifications, newest first.
	 */
	List<Notification> findByAssessment_User_IdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId);

	/**
	 * Phase 13-B — paginated user-scoped listings. No ordering is
	 * embedded in the names: callers supply the exact deterministic
	 * Sort (createdAt DESC, id DESC — the id tie-break is the approved
	 * ADR-026 refinement for stable pagination across equal
	 * timestamps) through the Pageable. Ownership is still derived
	 * through the assessment; both return bare lists (limit/offset
	 * only, no count query).
	 */
	List<Notification> findByAssessment_User_Id(UUID userId, Pageable pageable);

	/**
	 * Phase 13-B — paginated unread listing; same contract as
	 * {@link #findByAssessment_User_Id(UUID, Pageable)} plus the
	 * unread predicate.
	 */
	List<Notification> findByAssessment_User_IdAndReadAtIsNull(UUID userId, Pageable pageable);
}
