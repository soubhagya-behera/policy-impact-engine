package com.soubhagya.policyimpactengine.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.notification.application.NotificationService;
import com.soubhagya.policyimpactengine.notification.domain.Notification;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.recommendation.RecommendationEngine;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.UserService;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 10A — Testcontainers emission tests: the per-user flow from
 * assessment through recommendations to the in-app notification, the
 * NONE_REQUIRED silence rule, idempotent and concurrent emission,
 * failure-without-rollback with later healing, and user isolation.
 */
@SpringBootTest
@Testcontainers
class NotificationEmissionIntegrationTest {

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
	@Autowired private ImpactAssessmentService impactAssessmentService;
	@Autowired private RecommendationService recommendationService;
	@Autowired private RecommendationEngine recommendationEngine;
	@Autowired private NotificationService notificationService;
	@Autowired private PlatformTransactionManager transactionManager;

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
	void actionableAssessmentEmitsOneNotificationThroughHook() {
		Crafted crafted = craftTransition("THIRD_PARTY_SHARING", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 100);

		List<Recommendation> recommendations = recommendationService
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());

		assertThat(recommendations).isNotEmpty();
		assertThat(notificationRepository.count()).isEqualTo(1);
		Notification notification = notificationRepository.findAll().get(0);
		assertThat(notification.getAssessment().getId()).isEqualTo(crafted.assessment().getId());
		assertThat(notification.getCreatedAt()).isNotNull();
		assertThat(notification.getReadAt()).isNull();
		assertThat(notificationService.listNotifications(crafted.user().getId()))
				.extracting(Notification::getId)
				.containsExactly(notification.getId());
		assertThat(notificationService.listUnreadNotifications(crafted.user().getId()))
				.extracting(Notification::getId)
				.containsExactly(notification.getId());
	}

	@Test
	void noneRequiredAssessmentEmitsNothing() {
		Crafted crafted = craftTransition("COOKIES", "ADDED", 4,
				new BigDecimal("0.60"), new BigDecimal("2.40"), 16, ImpactBand.LOW);
		addBreakdown(crafted.assessment(), crafted.impact(), 2, 16);

		List<Recommendation> recommendations = recommendationService
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());

		assertThat(recommendations).hasSize(1);
		assertThat(recommendations.get(0).getRuleId()).isEqualTo("REC-NONE-REQUIRED");
		assertThat(notificationRepository.count()).isZero();
		assertThat(notificationService.emitForAssessment(crafted.user().getId(),
				crafted.assessment().getId())).isEmpty();
		assertThat(notificationRepository.count()).isZero();
	}

	@Test
	void reEmissionIsIdempotent() {
		Crafted crafted = craftTransition("THIRD_PARTY_SHARING", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 100);
		recommendationService.getOrCreateRecommendations(crafted.user().getId(),
				crafted.v2().getId());
		Notification first = notificationRepository.findAll().get(0);

		recommendationService.getOrCreateRecommendations(crafted.user().getId(),
				crafted.v2().getId());
		Optional<Notification> second = notificationService.emitForAssessment(
				crafted.user().getId(), crafted.assessment().getId());

		assertThat(notificationRepository.count()).isEqualTo(1);
		assertThat(second).isPresent();
		assertThat(second.get().getId()).isEqualTo(first.getId());
	}

	@Test
	void concurrentEmissionConvergesToOneRow() throws Exception {
		Crafted crafted = craftTransition("THIRD_PARTY_SHARING", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 100);
		// Persist the actionable set directly so both threads race inside
		// emission itself rather than inside recommendation derivation.
		recommendationRepository.saveAndFlush(new Recommendation(crafted.assessment(),
				"REC-REVIEW-SETTINGS", 3, RecommendationActionKind.REVIEW_SETTINGS,
				"THIRD_PARTY_SHARING", 100, ImpactBand.CRITICAL, 1));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<Optional<Notification>> first = executor.submit(() -> {
				start.await();
				return notificationService.emitForAssessment(crafted.user().getId(),
						crafted.assessment().getId());
			});
			Future<Optional<Notification>> second = executor.submit(() -> {
				start.await();
				return notificationService.emitForAssessment(crafted.user().getId(),
						crafted.assessment().getId());
			});
			start.countDown();
			Optional<Notification> n1 = first.get(60, TimeUnit.SECONDS);
			Optional<Notification> n2 = second.get(60, TimeUnit.SECONDS);

			assertThat(n1).isPresent();
			assertThat(n2).isPresent();
			assertThat(n1.get().getId()).isEqualTo(n2.get().getId());
			assertThat(notificationRepository.count()).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void emissionFailureKeepsRecommendationsAndLaterEmitHeals() {
		Crafted crafted = craftTransition("THIRD_PARTY_SHARING", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 100);
		NotificationService failingNotifications = mock(NotificationService.class);
		doThrow(new IllegalStateException("notify boom")).when(failingNotifications)
				.emitForAssessment(any(), any());
		RecommendationService failing = new RecommendationService(impactAssessmentService,
				breakdownRepository, recommendationRepository, recommendationEngine,
				failingNotifications, transactionManager);

		assertThatThrownBy(() -> failing.getOrCreateRecommendations(crafted.user().getId(),
				crafted.v2().getId()))
				.isInstanceOf(IllegalStateException.class);
		// Recommendations committed before the emission failure:
		assertThat(recommendationRepository.count()).isEqualTo(2);
		assertThat(notificationRepository.count()).isZero();

		// A later emit heals the missing notification idempotently.
		Optional<Notification> healed = notificationService.emitForAssessment(
				crafted.user().getId(), crafted.assessment().getId());
		assertThat(healed).isPresent();
		assertThat(notificationRepository.count()).isEqualTo(1);
		assertThat(recommendationRepository.count()).isEqualTo(2);
	}

	@Test
	void markReadFlowAndUserIsolation() {
		Crafted crafted = craftTransition("THIRD_PARTY_SHARING", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 100);
		recommendationService.getOrCreateRecommendations(crafted.user().getId(),
				crafted.v2().getId());
		Notification notification = notificationRepository.findAll().get(0);
		User other = userService.createUser();

		// Cross-user access behaves as not-found on every operation.
		assertThatThrownBy(() -> notificationService.emitForAssessment(other.getId(),
				crafted.assessment().getId()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notificationService.markRead(other.getId(), notification.getId()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(notificationService.listNotifications(other.getId())).isEmpty();

		Notification read = notificationService.markRead(crafted.user().getId(),
				notification.getId());
		assertThat(read.getReadAt()).isNotNull();
		assertThat(notificationService.listUnreadNotifications(crafted.user().getId())).isEmpty();
		assertThat(notificationService.listNotifications(crafted.user().getId())).hasSize(1);

		Notification again = notificationService.markRead(crafted.user().getId(),
				notification.getId());
		// Both rows come back from the database, so PostgreSQL's
		// microsecond timestamptz precision applies equally to each side.
		Notification reloaded = notificationRepository.findById(notification.getId())
				.orElseThrow();
		assertThat(again.getReadAt()).isEqualTo(reloaded.getReadAt());
	}

	private record Crafted(User user, Policy policy, PolicyVersion v1, PolicyVersion v2,
			ImpactAssessment assessment, ChangeImpact impact) {
	}

	/**
	 * Persists one controlled transition (v1 → v2) with exactly one change,
	 * match and system impact for the given concept, plus one user assessment
	 * whose aggregate equals the item's normalized score. Lets tests pin
	 * exact bands without depending on matcher keywords.
	 */
	private Crafted craftTransition(String conceptCode, String changeType, int conceptWeight,
			BigDecimal multiplier, BigDecimal baseScore, int normalized, ImpactBand band) {
		PrivacyConcept concept = conceptRepository.findByCode(conceptCode).orElseThrow();
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		String oldText = "old-" + UUID.randomUUID();
		String newText = "new-" + UUID.randomUUID() + " " + conceptCode.toLowerCase();
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, oldText, hasher.hash(oldText)));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, newText, hasher.hash(newText)));
		String changeOldText = "ADDED".equals(changeType) ? null : oldText;
		String changeNewText = "REMOVED".equals(changeType) ? null : newText;
		PolicyChangeRecord change = changeRepository.saveAndFlush(new PolicyChangeRecord(
				v1, v2, PolicyChangeType.valueOf(changeType), changeOldText, changeNewText, 0));
		ChangeConceptMatch match = matchRepository.saveAndFlush(new ChangeConceptMatch(
				change, concept, conceptCode.toLowerCase(), conceptCode + ":seed", "KEYWORD"));
		ChangeImpact impact = impactRepository.saveAndFlush(new ChangeImpact(match,
				conceptCode, changeType, conceptWeight, multiplier, baseScore,
				normalized, band, 1));
		User user = userService.createUser();
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(
				new ImpactAssessment(user, v2, v1, normalized, band, 1));
		return new Crafted(user, policy, v1, v2, assessment, impact);
	}

	private void addBreakdown(ImpactAssessment assessment, ChangeImpact impact,
			int effectiveSensitivity, int personalizedNormalized) {
		breakdownRepository.saveAndFlush(new ImpactAssessmentBreakdown(assessment, impact,
				effectiveSensitivity, personalizedNormalized,
				ImpactBand.fromNormalizedScore(personalizedNormalized), 1));
	}
}
