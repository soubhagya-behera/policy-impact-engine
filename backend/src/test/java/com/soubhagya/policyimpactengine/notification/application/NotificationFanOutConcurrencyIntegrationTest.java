package com.soubhagya.policyimpactengine.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.notification.domain.Notification;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.UserService;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 10B-1 — concurrency proof for automatic fan-out.
 *
 * <p>Two concurrent {@code fanOut} calls for the same owned
 * {@code NEW_VERSION} converge to one assessment, one recommendation
 * set, and one notification. No Java synchronization is involved: the
 * existing uniqueness guards (assessment, recommendation,
 * notification) plus the V11 claim serialization upstream decide the
 * single winner.
 */
@SpringBootTest
@Testcontainers
class NotificationFanOutConcurrencyIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private PolicyChangeRecordRepository changeRepository;
	@Autowired private PrivacyConceptRepository conceptRepository;
	@Autowired private ChangeConceptMatchRepository matchRepository;
	@Autowired private ChangeImpactRepository impactRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private ImpactAssessmentRepository assessmentRepository;
	@Autowired private ImpactAssessmentBreakdownRepository breakdownRepository;
	@Autowired private RecommendationRepository recommendationRepository;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private UserService userService;
	@Autowired private PolicyService policyService;
	@Autowired private NotificationFanOutService fanOutService;

	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void clean() {
		notificationRepository.deleteAll();
		recommendationRepository.deleteAll();
		breakdownRepository.deleteAll();
		assessmentRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void twoConcurrentFanOutsConvergeToOneSet() throws Exception {
		Fixture fixture = transition("THIRD_PARTY_SHARING", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL);
		PolicyObservationResult result = new PolicyObservationResult(
				fixture.policy().getId(), PolicyVersionObservationOutcome.NEW_VERSION, 2,
				"hash", Optional.empty(), Optional.empty());

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<?> first = executor.submit(() -> {
				start.await();
				fanOutService.fanOut(fixture.policy().getId(), result);
				return null;
			});
			Future<?> second = executor.submit(() -> {
				start.await();
				fanOutService.fanOut(fixture.policy().getId(), result);
				return null;
			});
			start.countDown();
			first.get(60, TimeUnit.SECONDS);
			second.get(60, TimeUnit.SECONDS);

			List<ImpactAssessment> assessments = assessmentRepository.findAll();
			assertThat(assessments).hasSize(1);
			assertThat(notificationRepository.count()).isEqualTo(1);
			long recommendations = recommendationRepository.count();
			assertThat(recommendations).isPositive();

			// A repeated fan-out converges to the same records.
			fanOutService.fanOut(fixture.policy().getId(), result);
			assertThat(assessmentRepository.count()).isEqualTo(1);
			assertThat(recommendationRepository.count()).isEqualTo(recommendations);
			assertThat(notificationRepository.count()).isEqualTo(1);
			List<Notification> notifications = notificationRepository.findAll();
			assertThat(notifications.get(0).getAssessment().getId())
					.isEqualTo(assessments.get(0).getId());
		}
		finally {
			executor.shutdownNow();
		}
	}

	private record Fixture(User user, Policy policy, PolicyVersion v2) {
	}

	/**
	 * Persists one owned transition (v1 → v2) with exactly one change,
	 * match, and system impact — but no assessment, recommendation, or
	 * notification, which the fan-out itself must create.
	 */
	private Fixture transition(String conceptCode, String changeType, int conceptWeight,
			BigDecimal multiplier, BigDecimal baseScore, int normalized, ImpactBand band) {
		PrivacyConcept concept = conceptRepository.findByCode(conceptCode).orElseThrow();
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		User user = userService.createUser();
		policyService.assignOwner(user.getId(), policy.getId());
		String oldText = "old-" + UUID.randomUUID();
		String newText = "new-" + UUID.randomUUID() + " " + conceptCode.toLowerCase();
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, oldText, hasher.hash(oldText)));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, newText, hasher.hash(newText)));
		PolicyChangeRecord change = changeRepository.saveAndFlush(new PolicyChangeRecord(
				v1, v2, PolicyChangeType.valueOf(changeType),
				"ADDED".equals(changeType) ? null : oldText,
				"REMOVED".equals(changeType) ? null : newText, 0));
		ChangeConceptMatch match = matchRepository.saveAndFlush(new ChangeConceptMatch(
				change, concept, conceptCode.toLowerCase(), conceptCode + ":seed", "KEYWORD"));
		impactRepository.saveAndFlush(new ChangeImpact(match, conceptCode, changeType,
				conceptWeight, multiplier, baseScore, normalized, band, 1));
		return new Fixture(user, policyRepository.findById(policy.getId()).orElseThrow(), v2);
	}
}
