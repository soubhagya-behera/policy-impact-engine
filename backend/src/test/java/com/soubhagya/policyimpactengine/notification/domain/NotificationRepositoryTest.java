package com.soubhagya.policyimpactengine.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 10A — Testcontainers repository tests for {@link Notification}.
 *
 * <p>Proves the V14 schema contract against real PostgreSQL: the
 * assessment FK, the UNIQUE(assessment_id) idempotency guard, the read_at
 * CHECK, newest-first user-scoped reads, and ownership derivation through
 * the assessment (no duplicated user_id anywhere).
 */
@SpringBootTest
@Testcontainers
class NotificationRepositoryTest {

	private static final Instant FIRST = Instant.parse("2026-09-18T10:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private ImpactAssessmentRepository assessmentRepository;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		notificationRepository.deleteAll();
		assessmentRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void roundTripReadsBackWithEagerAssessment() {
		Fixture fixture = fixture();
		Notification saved = notificationRepository.saveAndFlush(
				new Notification(fixture.assessment(), FIRST));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getReadAt()).isNull();
		Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getAssessment().getId()).isEqualTo(fixture.assessment().getId());
		assertThat(reloaded.getAssessment().getUser().getId()).isEqualTo(fixture.user().getId());
		assertThat(notificationRepository.findByAssessment_Id(fixture.assessment().getId()))
				.isPresent();
	}

	@Test
	void uniqueAssessmentIdRejectsSecondRow() {
		Fixture fixture = fixture();
		notificationRepository.saveAndFlush(new Notification(fixture.assessment(), FIRST));

		assertThatThrownBy(() -> notificationRepository.saveAndFlush(
				new Notification(fixture.assessment(), FIRST.plusSeconds(60))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void assessmentForeignKeyIsEnforced() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO notification (id, assessment_id, created_at) VALUES (?, ?, "
						+ "TIMESTAMP '2026-09-18 10:00:00+00')",
				UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void readAtCheckRejectsBackwardsTimestamp() {
		Fixture fixture = fixture();
		UUID id = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO notification (id, assessment_id, created_at, read_at) VALUES (?, ?, "
						+ "TIMESTAMP '2026-09-18 10:00:00+00', TIMESTAMP '2026-09-18 09:59:59+00')",
				id, fixture.assessment().getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(notificationRepository.findById(id)).isEmpty();
	}

	@Test
	void userScopedReadsAreNewestFirstAndIsolated() {
		Fixture first = fixture();
		Fixture second = fixture();
		notificationRepository.saveAndFlush(new Notification(first.assessment(), FIRST));
		notificationRepository.saveAndFlush(
				new Notification(second.assessment(), FIRST.plusSeconds(3600)));
		// A second assessment for the first user over the next transition,
		// notified earlier than both:
		PolicyVersion v3 = versionRepository.saveAndFlush(new PolicyVersion(first.policy(),
				3, "newer-" + UUID.randomUUID(), "hash-newer"));
		ImpactAssessment older = assessmentRepository.saveAndFlush(assessment(
				first.user(), v3, first.v2()));
		notificationRepository.saveAndFlush(new Notification(older, FIRST.minusSeconds(60)));

		List<Notification> feed = notificationRepository
				.findByAssessment_User_IdOrderByCreatedAtDesc(first.user().getId());
		assertThat(feed).extracting(notification -> notification.getAssessment().getId())
				.containsExactly(first.assessment().getId(), older.getId());
		assertThat(notificationRepository
				.findByAssessment_User_IdOrderByCreatedAtDesc(second.user().getId()))
				.extracting(notification -> notification.getAssessment().getId())
				.containsExactly(second.assessment().getId());

		// Mark the newest first-user notification read: the unread feed
		// narrows to the older one without touching the other user.
		Notification newest = feed.get(0);
		newest.markRead(FIRST.plusSeconds(7200));
		notificationRepository.saveAndFlush(newest);
		assertThat(notificationRepository
				.findByAssessment_User_IdAndReadAtIsNullOrderByCreatedAtDesc(
						first.user().getId()))
				.extracting(notification -> notification.getAssessment().getId())
				.containsExactly(older.getId());
		assertThat(notificationRepository
				.findByAssessment_User_IdAndReadAtIsNullOrderByCreatedAtDesc(
						second.user().getId()))
				.hasSize(1);
	}

	private Fixture fixture() {
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, "old-" + UUID.randomUUID(), "hash-old"));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, "new-" + UUID.randomUUID(), "hash-new"));
		User user = userRepository.saveAndFlush(new User());
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(
				assessment(user, v2, v1));
		return new Fixture(user, policy, v1, v2, assessment);
	}

	private ImpactAssessment assessment(User user, PolicyVersion v2, PolicyVersion v1) {
		return new ImpactAssessment(user, v2, v1, 80, ImpactBand.HIGH, 1);
	}

	private record Fixture(User user, Policy policy, PolicyVersion v1, PolicyVersion v2,
			ImpactAssessment assessment) {
	}
}
