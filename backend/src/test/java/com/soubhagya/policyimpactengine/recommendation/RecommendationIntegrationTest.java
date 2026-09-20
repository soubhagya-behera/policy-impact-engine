package com.soubhagya.policyimpactengine.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.DefaultPolicySimHash;
import com.soubhagya.policyimpactengine.diff.LineBasedPolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.DeterministicImpactScoringEngine;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.DeterministicConceptMatcher;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.notification.application.NotificationService;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.UserService;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 2R — Testcontainers integration tests: end-to-end derivation through
 * the real observation pipeline, idempotency, concurrency, user isolation,
 * rollback-on-failure with append-only assessments, append-only pending-set
 * semantics, and full traceability.
 */
@SpringBootTest
@Testcontainers
class RecommendationIntegrationTest {

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
	@Autowired private PolicyVersionService versionService;
	@Autowired private UserService userService;
	@Autowired private ImpactAssessmentService impactAssessmentService;
	@Autowired private RecommendationService recommendationService;
	@Autowired private RecommendationEngine recommendationEngine;
	@Autowired private NotificationService notificationService;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private PlatformTransactionManager transactionManager;

	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();
	private final PolicyDiffEngine diffEngine = new LineBasedPolicyDiffEngine();
	private final DeterministicConceptMatcher conceptMatcher = new DeterministicConceptMatcher();
	private final DeterministicImpactScoringEngine scoringEngine = new DeterministicImpactScoringEngine();

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
	void sharingChangeEndToEndProducesOptOutAndReview() {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("Acme Privacy Policy", "https://example.com/acme"));
		PolicyObservationPersistenceService persistence = observationService();
		String first = "We respect your privacy.\n";
		persistence.store(policy.getId(), first, hasher.hash(first));
		String second = "We share your location data with third parties and advertising partners.\n";
		persistence.store(policy.getId(), second, hasher.hash(second));
		PolicyVersion v2 = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		User user = userService.createUser();

		List<Recommendation> recommendations =
				recommendationService.getOrCreateRecommendations(user.getId(), v2.getId());

		// Single-line replacement → MODIFIED; default sensitivity 3 keeps the
		// personalized scores equal to the system scores:
		// THIRD_PARTY_SHARING 100/CRITICAL, LOCATION 80/CRITICAL,
		// ADVERTISING 70/HIGH. LOCATION never receives OPT_OUT_SHARING.
		assertThat(recommendations).extracting(
				Recommendation::getActionKind,
				Recommendation::getConceptCode,
				Recommendation::getRuleOrder,
				Recommendation::getPersonalizedNormalized
		).containsExactly(
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.OPT_OUT_SHARING, "THIRD_PARTY_SHARING", 2, 100),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.REVIEW_SETTINGS, "THIRD_PARTY_SHARING", 3, 100),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.REVIEW_SETTINGS, "LOCATION", 3, 80),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.OPT_OUT_SHARING, "ADVERTISING", 2, 70),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.REVIEW_SETTINGS, "ADVERTISING", 3, 70));
		assertThat(recommendations)
				.extracting(Recommendation::getActionKind)
				.doesNotContain(RecommendationActionKind.EXERCISE_DELETION,
						RecommendationActionKind.NONE_REQUIRED);
		assertThat(recommendations)
				.allSatisfy(r -> assertThat(r.getRecommendationRulesVersion()).isEqualTo(1));

		List<Recommendation> repeat =
				recommendationService.getOrCreateRecommendations(user.getId(), v2.getId());
		assertThat(repeat).extracting(Recommendation::getId)
				.isEqualTo(recommendations.stream().map(Recommendation::getId).toList());
		assertThat(recommendationRepository.count()).isEqualTo(5);
	}

	@Test
	void appendOnlyPendingSetTracksLatestAssessment() {
		Crafted v2Transition = craftTransition("THIRD_PARTY_SHARING", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL);
		addBreakdown(v2Transition.assessment(), v2Transition.impact(), 3, 100);
		List<Recommendation> v2Rows = recommendationService
				.getOrCreateRecommendations(v2Transition.user().getId(), v2Transition.v2().getId());
		List<java.util.UUID> v2RowIds = v2Rows.stream().map(Recommendation::getId).toList();

		// A new policy version arrives: build v3 with a DELETION_RIGHTS item.
		PrivacyConcept concept = conceptRepository.findByCode("DELETION_RIGHTS").orElseThrow();
		String oldText = "third version old " + UUID.randomUUID();
		String newText = "right to be forgotten " + UUID.randomUUID();
		PolicyVersion v3 = versionRepository.saveAndFlush(new PolicyVersion(
				v2Transition.policy(), 3, newText, hasher.hash(newText)));
		PolicyChangeRecord change = changeRepository.saveAndFlush(new PolicyChangeRecord(
				v2Transition.v2(), v3, PolicyChangeType.REMOVED, oldText, null, 0));
		ChangeConceptMatch match = matchRepository.saveAndFlush(new ChangeConceptMatch(
				change, concept, "delete", "DELETION_RIGHTS:delete", "KEYWORD"));
		ChangeImpact impact = impactRepository.saveAndFlush(new ChangeImpact(match,
				"DELETION_RIGHTS", "REMOVED", 8, new BigDecimal("0.80"),
				new BigDecimal("6.40"), 64, ImpactBand.HIGH, 1));
		ImpactAssessment v3Assessment = assessmentRepository.saveAndFlush(
				new ImpactAssessment(v2Transition.user(), v3, v2Transition.v2(), 64,
						ImpactBand.HIGH, 1));
		addBreakdown(v3Assessment, impact, 3, 64);

		List<Recommendation> v3Rows = recommendationService
				.getOrCreateRecommendations(v2Transition.user().getId(), v3.getId());

		// Append-only: the v2 rows were never mutated or deleted...
		assertThat(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						v2Transition.assessment().getId()))
				.extracting(Recommendation::getId)
				.isEqualTo(v2RowIds);
		// ...and the pending set for the user is the latest assessment's set.
		assertThat(v3Rows)
				.extracting(Recommendation::getActionKind, Recommendation::getConceptCode)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(
								RecommendationActionKind.EXERCISE_DELETION, "DELETION_RIGHTS"),
						org.assertj.core.groups.Tuple.tuple(
								RecommendationActionKind.REVIEW_SETTINGS, "DELETION_RIGHTS"));
		assertThat(recommendationService
				.getRecommendations(v2Transition.user().getId(), v3.getId()))
				.extracting(Recommendation::getId)
				.isEqualTo(v3Rows.stream().map(Recommendation::getId).toList());
		assertThat(recommendationRepository.count()).isEqualTo(4);
	}

	@Test
	void completeTraceabilityFromRecommendationToPolicy() {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("Trace Policy", "https://example.com/trace"));
		PolicyObservationPersistenceService persistence = observationService();
		String first = "We respect your privacy.\n";
		persistence.store(policy.getId(), first, hasher.hash(first));
		String second = "We share your location data with third parties and advertising partners.\n";
		persistence.store(policy.getId(), second, hasher.hash(second));
		PolicyVersion v2 = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		User user = userService.createUser();

		List<Recommendation> recommendations =
				recommendationService.getOrCreateRecommendations(user.getId(), v2.getId());
		assertThat(recommendations).isNotEmpty();
		assertThat(recommendations.get(0).getRuleId()).isEqualTo("REC-SHARING-OPT-OUT");

		// Navigate the full chain inside one transaction so lazy associations
		// can be initialized: recommendation → assessment → breakdown →
		// change impact → concept match → change record → version → policy.
		new TransactionTemplate(transactionManager).execute(status -> {
			ImpactAssessment assessment = assessmentRepository
					.findByUser_IdAndNewVersion_Id(user.getId(), v2.getId()).orElseThrow();
			List<Recommendation> rows = recommendationRepository
					.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
							assessment.getId());
			Recommendation row = rows.get(0);
			assertThat(row.getRuleId()).isEqualTo("REC-SHARING-OPT-OUT");
			assertThat(assessment.getAggregateScore()).isEqualTo(100);
			assertThat(assessment.getAggregateBand()).isEqualTo(ImpactBand.CRITICAL);
			assertThat(assessment.getUser().getId()).isEqualTo(user.getId());
			assertThat(assessment.getNewVersion().getId()).isEqualTo(v2.getId());
			assertThat(assessment.getPreviousVersion().getId()).isNotEqualTo(v2.getId());
			assertThat(assessment.getId()).isEqualTo(row.getAssessment().getId());

			List<ImpactAssessmentBreakdown> breakdowns = breakdownRepository
					.findByAssessment_IdOrderByConceptCodeAsc(assessment.getId());
			assertThat(breakdowns).hasSize(3);
			for (ImpactAssessmentBreakdown breakdown : breakdowns) {
				ChangeImpact impact = breakdown.getChangeImpact();
				ChangeConceptMatch match = impact.getMatch();
				PolicyChangeRecord change = match.getChange();
				assertThat(impact.getId()).isNotNull();
				assertThat(match.getId()).isNotNull();
				assertThat(change.getNewVersion().getId()).isEqualTo(v2.getId());
				assertThat(change.getNewVersion().getPolicy().getId()).isEqualTo(policy.getId());
				assertThat(match.getConcept().getCode()).isEqualTo(breakdown.getConceptCode());
				assertThat(breakdown.getPersonalizedNormalized())
						.isEqualTo(impact.getNormalizedScore());
			}
			return null;
		});
	}

	private PolicyObservationPersistenceService observationService() {
		return new PolicyObservationPersistenceService(versionService, versionRepository,
				diffEngine, changeRepository, new DefaultPolicySimHash(), conceptRepository,
				matchRepository, conceptMatcher, impactRepository, scoringEngine,
				transactionManager);
	}

	@Test
	void deletionRightsRemovedCraftedProducesExerciseDeletionAndReview() {
		Crafted crafted = craftTransition("DELETION_RIGHTS", "REMOVED", 8,
				new BigDecimal("0.80"), new BigDecimal("6.40"), 64, ImpactBand.HIGH);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 64);

		List<Recommendation> recommendations = recommendationService
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());

		assertThat(recommendations).extracting(
				Recommendation::getActionKind,
				Recommendation::getConceptCode,
				Recommendation::getRuleId,
				Recommendation::getRuleOrder
		).containsExactly(
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.EXERCISE_DELETION, "DELETION_RIGHTS",
						"REC-DELETION-RIGHTS-LOST", 1),
				org.assertj.core.groups.Tuple.tuple(
						RecommendationActionKind.REVIEW_SETTINGS, "DELETION_RIGHTS",
						"REC-REVIEW-SETTINGS", 3));
		assertThat(recommendations)
				.extracting(Recommendation::getActionKind)
				.doesNotContain(RecommendationActionKind.NONE_REQUIRED);
	}

	@Test
	void lowImpactCraftedProducesSingleNoneRequired() {
		Crafted crafted = craftTransition("COOKIES", "ADDED", 4,
				new BigDecimal("0.60"), new BigDecimal("2.40"), 16, ImpactBand.LOW);
		addBreakdown(crafted.assessment(), crafted.impact(), 2, 16);

		List<Recommendation> recommendations = recommendationService
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());

		assertThat(recommendations).hasSize(1);
		Recommendation none = recommendations.get(0);
		assertThat(none.getActionKind()).isEqualTo(RecommendationActionKind.NONE_REQUIRED);
		assertThat(none.getRuleId()).isEqualTo("REC-NONE-REQUIRED");
		assertThat(none.getRuleOrder()).isEqualTo(4);
		assertThat(none.getConceptCode()).isNull();
		// The closure row snapshots the user's aggregate score/band:
		assertThat(none.getPersonalizedNormalized()).isEqualTo(16);
		assertThat(none.getPersonalizedBand()).isEqualTo(ImpactBand.LOW);
	}

	@Test
	void getOrCreateIsIdempotentAndReadOnlyAccessWorks() {
		Crafted crafted = craftTransition("THIRD_PARTY_SHARING", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 100);

		List<Recommendation> first = recommendationService
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());
		List<Recommendation> second = recommendationService
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());
		assertThat(second).extracting(Recommendation::getId)
				.isEqualTo(first.stream().map(Recommendation::getId).toList());
		assertThat(recommendationRepository.count()).isEqualTo(2);

		List<Recommendation> read = recommendationService
				.getRecommendations(crafted.user().getId(), crafted.v2().getId());
		assertThat(read).extracting(Recommendation::getId)
				.isEqualTo(first.stream().map(Recommendation::getId).toList());

		UUID strangerVersionId = UUID.randomUUID();
		assertThatThrownBy(() -> recommendationService
				.getRecommendations(crafted.user().getId(), strangerVersionId))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> recommendationService
				.getOrCreateRecommendations(null, crafted.v2().getId()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void concurrentGetOrCreateProducesExactlyOneSet() throws Exception {
		Crafted crafted = craftTransition("ADVERTISING", "MODIFIED", 7,
				new BigDecimal("1.00"), new BigDecimal("7.00"), 70, ImpactBand.HIGH);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 70);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<List<Recommendation>> f1 = executor.submit(() -> {
				start.await();
				return recommendationService
						.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());
			});
			Future<List<Recommendation>> f2 = executor.submit(() -> {
				start.await();
				return recommendationService
						.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());
			});
			start.countDown();
			List<Recommendation> r1 = f1.get(60, TimeUnit.SECONDS);
			List<Recommendation> r2 = f2.get(60, TimeUnit.SECONDS);
			assertThat(r1).extracting(Recommendation::getId)
					.isEqualTo(r2.stream().map(Recommendation::getId).toList());
			// Exactly one OPT_OUT row for ADVERTISING and one REVIEW row:
			assertThat(r1).hasSize(2);
			assertThat(recommendationRepository.count()).isEqualTo(2);
			assertThat(recommendationRepository.findAll())
					.extracting(Recommendation::getActionKind)
					.containsExactlyInAnyOrder(RecommendationActionKind.OPT_OUT_SHARING,
							RecommendationActionKind.REVIEW_SETTINGS);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private record Crafted(User user, Policy policy, PolicyVersion v1, PolicyVersion v2,
			ImpactAssessment assessment, ChangeImpact impact) {
	}

	@Test
	void userIsolationAcrossAssessmentsAndReads() {
		Crafted crafted = craftTransition("LOCATION", "MODIFIED", 8,
				new BigDecimal("1.00"), new BigDecimal("8.00"), 80, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 80);

		List<Recommendation> ownerRows = recommendationService
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());
		assertThat(ownerRows).hasSize(1);

		User other = userService.createUser();
		// Regression: the other user has no assessment for this version, so
		// the user-scoped assessment lookup fails before any recommendation
		// row is touched — a user can never retrieve another user's
		// assessment rows, and rows carry no duplicated user_id that could
		// bypass the assessment relationship.
		assertThatThrownBy(() -> recommendationService
				.getRecommendations(other.getId(), crafted.v2().getId()))
				.isInstanceOf(IllegalArgumentException.class);

		// Deriving for the other user creates their own assessment + own rows.
		List<Recommendation> otherRows = recommendationService
				.getOrCreateRecommendations(other.getId(), crafted.v2().getId());
		assertThat(otherRows).hasSize(1);
		assertThat(otherRows.get(0).getId()).isNotEqualTo(ownerRows.get(0).getId());
		assertThat(assessmentRepository.count()).isEqualTo(2);
		assertThat(recommendationRepository.count()).isEqualTo(2);

		// Each set is attached to its owner's assessment: ownership is
		// derived through the assessment relationship.
		ImpactAssessment ownerAssessment = assessmentRepository
				.findByUser_IdAndNewVersion_Id(crafted.user().getId(), crafted.v2().getId())
				.orElseThrow();
		ImpactAssessment otherAssessment = assessmentRepository
				.findByUser_IdAndNewVersion_Id(other.getId(), crafted.v2().getId())
				.orElseThrow();
		assertThat(otherAssessment.getId()).isNotEqualTo(ownerAssessment.getId());
		assertThat(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						ownerAssessment.getId()))
				.extracting(Recommendation::getId)
				.isEqualTo(ownerRows.stream().map(Recommendation::getId).toList());
		assertThat(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						otherAssessment.getId()))
				.extracting(Recommendation::getId)
				.isEqualTo(otherRows.stream().map(Recommendation::getId).toList());
	}

	@Test
	void engineFailureRollsBackAndKeepsAssessment() {
		Crafted crafted = craftTransition("THIRD_PARTY_SHARING", "REMOVED", 10,
				new BigDecimal("0.80"), new BigDecimal("8.00"), 80, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 80);

		RecommendationEngine failingEngine = input -> {
			throw new IllegalStateException("engine boom");
		};
		RecommendationService failing = new RecommendationService(impactAssessmentService,
				breakdownRepository, recommendationRepository, failingEngine,
				notificationService, transactionManager);

		assertThatThrownBy(() -> failing
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId()))
				.isInstanceOf(IllegalStateException.class);
		assertThat(recommendationRepository.count()).isZero();
		// The previously committed assessment remains untouched:
		assertThat(assessmentRepository.findByUser_IdAndNewVersion_Id(
				crafted.user().getId(), crafted.v2().getId())).isPresent();

		// Retrying with the real engine succeeds and persists the set.
		List<Recommendation> recovered = recommendationService
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId());
		assertThat(recovered).hasSize(2);
	}

	@Test
	void flushFailureRollsBackPendingWritesAndKeepsAssessment() {
		Crafted crafted = craftTransition("THIRD_PARTY_SHARING", "REMOVED", 10,
				new BigDecimal("0.80"), new BigDecimal("8.00"), 80, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 80);

		// Delegating repository whose flush() throws after rows were staged:
		RecommendationRepository failingRepository = (RecommendationRepository) Proxy
				.newProxyInstance(RecommendationRepository.class.getClassLoader(),
						new Class<?>[] { RecommendationRepository.class },
						(proxy, method, args) -> {
							if (method.getName().equals("flush")) {
								throw new IllegalStateException("flush boom");
							}
							return method.invoke(recommendationRepository, args);
						});
		RecommendationService failing = new RecommendationService(impactAssessmentService,
				breakdownRepository, failingRepository, recommendationEngine,
				notificationService, transactionManager);

		assertThatThrownBy(() -> failing
				.getOrCreateRecommendations(crafted.user().getId(), crafted.v2().getId()))
				.isInstanceOf(IllegalStateException.class);
		// The rollback discards the staged recommendation writes while the
		// previously committed assessment remains:
		assertThat(recommendationRepository.count()).isZero();
		assertThat(assessmentRepository.findByUser_IdAndNewVersion_Id(
				crafted.user().getId(), crafted.v2().getId())).isPresent();
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
		// Respect the persisted text-shape rule: ADDED has no old text,
		// REMOVED has no new text, MODIFIED has both.
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
