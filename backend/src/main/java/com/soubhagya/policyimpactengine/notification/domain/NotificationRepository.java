package com.soubhagya.policyimpactengine.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
