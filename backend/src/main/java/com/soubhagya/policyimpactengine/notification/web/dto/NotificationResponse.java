package com.soubhagya.policyimpactengine.notification.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.notification.domain.Notification;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;

/**
 * Phase 10B-2B — authenticated notification feed item.
 *
 * <p>Exactly seven fields: the notification identity and lifecycle plus
 * the assessment reference resolved to the owning policy and version
 * number. No user identity, credentials, scores, bands, recommendations,
 * concepts, or entities are exposed.
 *
 * <p>Mapping must execute inside the service transaction that loaded
 * the notification: {@code assessment.newVersion} and
 * {@code newVersion.policy} are lazy associations.
 */
public record NotificationResponse(

		UUID id,
		UUID assessmentId,
		UUID policyId,
		int versionNumber,
		Instant createdAt,
		Instant readAt,
		boolean read

) {

	public static NotificationResponse from(Notification notification) {
		if (notification == null) {
			throw new IllegalArgumentException("Notification must not be null");
		}
		PolicyVersion newVersion = notification.getAssessment().getNewVersion();
		return new NotificationResponse(
				notification.getId(),
				notification.getAssessment().getId(),
				newVersion.getPolicy().getId(),
				newVersion.getVersionNumber(),
				notification.getCreatedAt(),
				notification.getReadAt(),
				notification.getReadAt() != null);
	}

}
