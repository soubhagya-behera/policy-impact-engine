package com.soubhagya.policyimpactengine.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.user.domain.User;

/**
 * Phase 10A — pure unit tests for the {@link Notification} lifecycle.
 *
 * <p>No Spring context, no database. Entities are constructed directly;
 * strictly the constructor validation and the single mark-read transition
 * are exercised here. Persistence guards (UNIQUE, FK, CHECK) belong to the
 * repository tests.
 */
class NotificationTest {

	private static final Instant CREATED = Instant.parse("2026-09-18T10:00:00Z");

	@Test
	void newNotificationIsUnread() {
		Notification notification = new Notification(assessment(), CREATED);

		assertThat(notification.getAssessment()).isNotNull();
		assertThat(notification.getCreatedAt()).isEqualTo(CREATED);
		assertThat(notification.getReadAt()).isNull();
	}

	@Test
	void constructorRejectsNulls() {
		assertThatThrownBy(() -> new Notification(null, CREATED))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(assessment(), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void firstMarkReadStampsReadAt() {
		Notification notification = new Notification(assessment(), CREATED);
		Instant readAt = CREATED.plusSeconds(60);

		notification.markRead(readAt);

		assertThat(notification.getReadAt()).isEqualTo(readAt);
	}

	@Test
	void secondMarkReadIsNoOpAndNeverMovesBackwards() {
		Notification notification = new Notification(assessment(), CREATED);
		Instant first = CREATED.plusSeconds(60);
		notification.markRead(first);

		notification.markRead(first.plusSeconds(3600));

		assertThat(notification.getReadAt()).isEqualTo(first);
	}

	@Test
	void markReadRejectsNullAndBackwardsTimestamps() {
		Notification notification = new Notification(assessment(), CREATED);

		assertThatThrownBy(() -> notification.markRead(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification.markRead(CREATED.minusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(notification.getReadAt()).isNull();
	}

	private ImpactAssessment assessment() {
		User user = new User();
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		PolicyVersion v1 = new PolicyVersion(policy, 1, "old", "hash-old");
		PolicyVersion v2 = new PolicyVersion(policy, 2, "new", "hash-new");
		return new ImpactAssessment(user, v2, v1, 80, ImpactBand.HIGH, 1);
	}
}
