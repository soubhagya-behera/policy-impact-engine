package com.soubhagya.policyimpactengine.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyObservationScheduler;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.application.StaleAttemptRecovery;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
import com.soubhagya.policyimpactengine.notification.application.NotificationFanOutService;
import com.soubhagya.policyimpactengine.notification.domain.Notification;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.JsoupPolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetchException;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.UserService;
import com.soubhagya.policyimpactengine.user.domain.User;

/**
 * Phase 10B-1 — Testcontainers ownership and automatic fan-out tests.
 *
 * <p>Drives the real scheduler tick through the real observation
 * pipeline with a stub fetcher: an owned {@code NEW_VERSION}
 * produces the owner's assessment, recommendations, and
 * notification; an unowned {@code NEW_VERSION} stays silent; a
 * repeated {@code UNCHANGED} observation stays silent; a
 * {@code NONE_REQUIRED} assessment produces no notification; and the
 * existing notification idempotency, V11 claim serialization,
 * retry/backoff, and stale-recovery behavior remain intact. A fan-out
 * failure leaves the successful observation (attempt state and
 * {@code next_check_at}) untouched.
 */
@SpringBootTest
@Testcontainers
class PolicyOwnershipFanOutIntegrationTest {

	private static final Instant TICK_START = Instant.parse("2026-09-18T10:00:00Z");
	private static final Duration INTERVAL = Duration.ofHours(24);

	private static final String V1_HTML = "<html><body><p>Welcome to our privacy policy.</p>"
			+ "<p>This policy applies to all users of the service.</p></body></html>";
	private static final String V2_ACTIONABLE_HTML = "<html><body><p>Welcome to our privacy policy.</p>"
			+ "<p>This policy applies to all users of the service.</p>"
			+ "<p>We share your location data with third-party advertising partners.</p></body></html>";
	private static final String V2_NEUTRAL_HTML = "<html><body><p>Welcome to our privacy policy.</p>"
			+ "<p>This policy applies to all users of the service.</p>"
			+ "<p>Our office moved to a new building downtown.</p></body></html>";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private PolicyChangeRecordRepository changeRepository;
	@Autowired private ChangeConceptMatchRepository matchRepository;
	@Autowired private ChangeImpactRepository impactRepository;
	@Autowired private PolicyFetchAttemptRepository attemptRepository;
	@Autowired private ImpactAssessmentRepository assessmentRepository;
	@Autowired private ImpactAssessmentBreakdownRepository breakdownRepository;
	@Autowired private RecommendationRepository recommendationRepository;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private PolicyFetchAttemptService attemptService;
	@Autowired private PolicyObservationPersistenceService persistenceService;
	@Autowired private ImpactAssessmentService assessmentService;
	@Autowired private RecommendationService recommendationService;
	@Autowired private PolicyService policyService;
	@Autowired private UserService userService;
	@Autowired private StaleAttemptRecovery staleRecovery;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PlatformTransactionManager transactionManager;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void clean() {
		notificationRepository.deleteAll();
		recommendationRepository.deleteAll();
		breakdownRepository.deleteAll();
		assessmentRepository.deleteAll();
		attemptRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void ownedNewVersionProducesFullFlowAndUnchangedStaysSilent() {
		User owner = userService.createUser();
		Policy policy = ownedPolicy(owner);
		MutableFetcher fetcher = fetcher(Map.of(policy.getUrl(), V1_HTML));

		scheduler(fetcher).checkDuePolicies();
		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId()))
				.hasSize(1);
		assertThat(assessmentRepository.count()).isZero();
		assertThat(notificationRepository.count()).isZero();

		fetcher.put(policy.getUrl(), V2_ACTIONABLE_HTML);
		reDue(policy);
		scheduler(fetcher).checkDuePolicies();

		List<PolicyVersion> versions = versionRepository
				.findByPolicy_IdOrderByVersionNumberAsc(policy.getId());
		assertThat(versions).hasSize(2);
		List<ImpactAssessment> assessments = assessmentRepository.findAll();
		assertThat(assessments).hasSize(1);
		assertThat(assessments.get(0).getUser().getId()).isEqualTo(owner.getId());
		assertThat(assessments.get(0).getNewVersion().getId())
				.isEqualTo(versions.get(1).getId());
		List<Recommendation> recommendations = recommendationRepository.findAll();
		assertThat(recommendations).isNotEmpty();
		assertThat(recommendations)
				.anyMatch(row -> !"REC-NONE-REQUIRED".equals(row.getRuleId()));
		List<Notification> notifications = notificationRepository.findAll();
		assertThat(notifications).hasSize(1);
		assertThat(notifications.get(0).getAssessment().getId())
				.isEqualTo(assessments.get(0).getId());

		reDue(policy);
		scheduler(fetcher).checkDuePolicies();

		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId()))
				.hasSize(2);
		assertThat(assessmentRepository.count()).isEqualTo(1);
		assertThat(recommendationRepository.count()).isEqualTo(recommendations.size());
		assertThat(notificationRepository.count()).isEqualTo(1);
		assertThat(attemptRepository.findAll())
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactlyInAnyOrder(PolicyFetchAttemptStatus.SUCCESS,
						PolicyFetchAttemptStatus.SUCCESS,
						PolicyFetchAttemptStatus.SKIPPED_UNCHANGED);
	}

	@Test
	void unownedNewVersionRemainsSilent() {
		Policy policy = duePolicy(PolicyStatus.ACTIVE);
		MutableFetcher fetcher = fetcher(Map.of(policy.getUrl(), V1_HTML));

		scheduler(fetcher).checkDuePolicies();
		fetcher.put(policy.getUrl(), V2_ACTIONABLE_HTML);
		reDue(policy);
		scheduler(fetcher).checkDuePolicies();

		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId()))
				.hasSize(2);
		assertThat(attemptRepository.findAll())
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactlyInAnyOrder(PolicyFetchAttemptStatus.SUCCESS,
						PolicyFetchAttemptStatus.SUCCESS);
		assertThat(assessmentRepository.count()).isZero();
		assertThat(recommendationRepository.count()).isZero();
		assertThat(notificationRepository.count()).isZero();
	}

	@Test
	void noneRequiredAssessmentProducesNoNotification() {
		User owner = userService.createUser();
		Policy policy = ownedPolicy(owner);
		MutableFetcher fetcher = fetcher(Map.of(policy.getUrl(), V1_HTML));

		scheduler(fetcher).checkDuePolicies();
		fetcher.put(policy.getUrl(), V2_NEUTRAL_HTML);
		reDue(policy);
		scheduler(fetcher).checkDuePolicies();

		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId()))
				.hasSize(2);
		List<ImpactAssessment> assessments = assessmentRepository.findAll();
		assertThat(assessments).hasSize(1);
		List<Recommendation> recommendations = recommendationRepository.findAll();
		assertThat(recommendations).hasSize(1);
		assertThat(recommendations.get(0).getRuleId()).isEqualTo("REC-NONE-REQUIRED");
		assertThat(notificationRepository.count()).isZero();
	}

	@Test
	void repeatFanOutStaysIdempotent() {
		User owner = userService.createUser();
		Policy policy = ownedPolicy(owner);
		MutableFetcher fetcher = fetcher(Map.of(policy.getUrl(), V1_HTML));

		scheduler(fetcher).checkDuePolicies();
		fetcher.put(policy.getUrl(), V2_ACTIONABLE_HTML);
		reDue(policy);
		scheduler(fetcher).checkDuePolicies();

		UUID assessmentId = assessmentRepository.findAll().get(0).getId();
		UUID notificationId = notificationRepository.findAll().get(0).getId();
		long recommendations = recommendationRepository.count();

		NotificationFanOutService fanOut = fanOut();
		PolicyVersion v2 = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		fanOut.fanOut(policy.getId(), observationResult(policy.getId(), 2));

		assertThat(assessmentRepository.count()).isEqualTo(1);
		assertThat(assessmentRepository.findAll().get(0).getId()).isEqualTo(assessmentId);
		assertThat(recommendationRepository.count()).isEqualTo(recommendations);
		assertThat(notificationRepository.count()).isEqualTo(1);
		assertThat(notificationRepository.findAll().get(0).getId()).isEqualTo(notificationId);
		assertThat(v2.getId()).isNotNull();
	}

	@Test
	void v11ClaimStillSerializesScheduledTick() {
		Policy policy = duePolicy(PolicyStatus.ACTIVE);
		MutableFetcher fetcher = fetcher(Map.of(policy.getUrl(), V1_HTML));
		// Another trigger owns this policy right now: the tick must skip
		// it without fetching and still advance its next check.
		attemptService.beginAttempt(reload(policy), PolicyFetchAttemptTrigger.MANUAL);

		scheduler(fetcher).checkDuePolicies();

		assertThat(versionRepository.count()).isZero();
		assertThat(attemptRepository.count()).isEqualTo(1);
		assertThat(attemptRepository.findAll().get(0).getStatus())
				.isEqualTo(PolicyFetchAttemptStatus.IN_PROGRESS);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
		assertThat(assessmentRepository.count()).isZero();
		assertThat(notificationRepository.count()).isZero();
	}

	@Test
	void transientFailureStillBacksOffWithoutFanOut() {
		User owner = userService.createUser();
		Policy policy = ownedPolicy(owner);
		PolicyFetcher failing = url -> {
			throw new PolicyFetchException("connection timeout", null, true);
		};

		scheduler(failing).checkDuePolicies();

		List<PolicyFetchAttempt> attempts = attemptRepository.findAll();
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(attempts.get(0).getFailureKind())
				.isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		// Backoff reschedule is preserved by the move-guard instead of
		// being overwritten with the uniform tick advancement.
		assertThat(reload(policy).getNextCheckAt()).isAfter(TICK_START);
		assertThat(reload(policy).getNextCheckAt()).isNotEqualTo(TICK_START.plus(INTERVAL));
		assertThat(versionRepository.count()).isZero();
		assertThat(assessmentRepository.count()).isZero();
		assertThat(notificationRepository.count()).isZero();
	}

	@Test
	void staleRecoveryStillReapsLeaseExpiredRow() {
		Policy policy = duePolicy(PolicyStatus.ACTIVE);
		PolicyFetchAttempt stale = attemptService.beginAttempt(reload(policy),
				PolicyFetchAttemptTrigger.SCHEDULED);
		Instant staleStart = TICK_START.minus(Duration.ofMinutes(31));
		jdbcTemplate.update("UPDATE policy_fetch_attempt SET started_at = ? WHERE id = ?",
				java.sql.Timestamp.from(staleStart), stale.getId());

		int recovered = staleRecovery.recoverStaleAttempts(TICK_START);

		assertThat(recovered).isEqualTo(1);
		PolicyFetchAttempt reaped = attemptRepository.findById(stale.getId()).orElseThrow();
		assertThat(reaped.getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(reaped.getFailureKind()).isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		assertThat(reaped.getAttemptNumber()).isEqualTo(stale.getAttemptNumber());
		// V11 slot freed: the next check claims cleanly as attempt 2.
		PolicyFetchAttempt next = attemptService.beginAttempt(reload(policy),
				PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(next.getAttemptNumber()).isEqualTo(2);
	}

	@Test
	void fanOutFailureLeavesObservationSuccessful() {
		User owner = userService.createUser();
		Policy policy = ownedPolicy(owner);
		MutableFetcher fetcher = fetcher(Map.of(policy.getUrl(), V1_HTML));
		scheduler(fetcher).checkDuePolicies();

		fetcher.put(policy.getUrl(), V2_ACTIONABLE_HTML);
		reDue(policy);
		RecommendationService failingRecommendations = mock(RecommendationService.class);
		doThrow(new IllegalStateException("fan-out boom")).when(failingRecommendations)
				.getOrCreateRecommendations(any(), any());
		NotificationFanOutService failingFanOut = new NotificationFanOutService(
				policyRepository, versionRepository, assessmentService, failingRecommendations);
		PolicyObservationScheduler scheduler = new PolicyObservationScheduler(policyRepository,
				orchestrator(fetcher), failingFanOut, transactionManager,
				Clock.fixed(TICK_START, ZoneOffset.UTC), INTERVAL);

		scheduler.checkDuePolicies();

		// The observation itself succeeded: version 2 exists, both
		// attempts are SUCCESS (never FAILED), and next_check_at
		// advanced uniformly. The fan-out failure changed none of that.
		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId()))
				.hasSize(2);
		List<PolicyFetchAttempt> attempts = attemptRepository.findAll();
		assertThat(attempts).hasSize(2);
		assertThat(attempts)
				.filteredOn(attempt -> attempt.getStatus() == PolicyFetchAttemptStatus.SUCCESS)
				.hasSize(2);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
		// The assessment transaction committed before the recommendation
		// failure; recommendations and the notification are missing —
		// the accepted healable gap.
		assertThat(assessmentRepository.count()).isEqualTo(1);
		assertThat(recommendationRepository.count()).isZero();
		assertThat(notificationRepository.count()).isZero();

		// A repeated fan-out for the same version heals the gap.
		fanOut().fanOut(policy.getId(), observationResult(policy.getId(), 2));
		assertThat(assessmentRepository.count()).isEqualTo(1);
		assertThat(recommendationRepository.count()).isPositive();
		assertThat(notificationRepository.count()).isEqualTo(1);
	}

	private PolicyObservationScheduler scheduler(PolicyFetcher fetcher) {
		return new PolicyObservationScheduler(policyRepository, orchestrator(fetcher), fanOut(),
				transactionManager, Clock.fixed(TICK_START, ZoneOffset.UTC), INTERVAL);
	}

	private PolicyObservationService orchestrator(PolicyFetcher fetcher) {
		return new PolicyObservationService(policyRepository, fetcher, extractor, normalizer,
				hasher, persistenceService, attemptService, testRetryPolicy(), transactionManager);
	}

	private NotificationFanOutService fanOut() {
		return new NotificationFanOutService(policyRepository, versionRepository,
				assessmentService, recommendationService);
	}

	private RetryPolicy testRetryPolicy() {
		return new RetryPolicy(5, Duration.ofMinutes(5), 2.0, Duration.ofHours(6),
				Duration.ofHours(24), new Random());
	}

	private com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult observationResult(
			UUID policyId, int versionNumber) {
		return new com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult(
				policyId,
				com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome.NEW_VERSION,
				versionNumber, "hash", Optional.empty(), Optional.empty());
	}

	private Policy duePolicy(PolicyStatus status) {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setStatus(status);
		policy.setNextCheckAt(TICK_START.minusSeconds(60));
		return policyRepository.saveAndFlush(policy);
	}

	private Policy ownedPolicy(User owner) {
		Policy policy = duePolicy(PolicyStatus.ACTIVE);
		policyService.assignOwner(owner.getId(), policy.getId());
		return policyRepository.findById(policy.getId()).orElseThrow();
	}

	private void reDue(Policy policy) {
		Policy reloaded = reload(policy);
		reloaded.setNextCheckAt(TICK_START.minusSeconds(10));
		policyRepository.saveAndFlush(reloaded);
	}

	private Policy reload(Policy policy) {
		return policyRepository.findById(policy.getId()).orElseThrow();
	}

	private MutableFetcher fetcher(Map<String, String> routes) {
		return new MutableFetcher(new ConcurrentHashMap<>(routes));
	}

	private static final class MutableFetcher implements PolicyFetcher {
		private final Map<String, String> routes;

		MutableFetcher(Map<String, String> routes) {
			this.routes = new ConcurrentHashMap<>(routes);
		}

		void put(String url, String html) {
			routes.put(url, html);
		}

		@Override
		public FetchResult fetch(String url) {
			String html = routes.get(url);
			if (html == null) {
				throw new PolicyFetchException("no fixture for " + url);
			}
			return new FetchResult(url, 200, "text/html", html);
		}
	}
}
