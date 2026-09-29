package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.application.AuditVerificationService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditVerificationResult;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyObservationScheduler;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.notification.application.NotificationFanOutService;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.JsoupPolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetchException;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 14-C/4a — Testcontainers lifecycle regression for the
 * {@code ACTIVE → ARCHIVED} transition (see DECISIONS.md ADR-030):
 * a policy with fully populated history (versions, changes,
 * matches, impacts, assessment with breakdowns, recommendations,
 * notification, fetch attempts) keeps every row across the
 * archive; the scheduler ignores the archived policy; manual
 * observation still completes with silent fan-out; an archive
 * landing mid-observation leaves no FK failure; and the audit
 * chain (including the single {@code POLICY_ARCHIVED} row)
 * verifies VALID. Production code is untouched — failures here
 * distinguish a wrong test premise from an ADR-030 contradiction.
 */
@SpringBootTest
@Testcontainers
class PolicyArchiveLifecycleRegressionTest {

	private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
	private static final Duration INTERVAL = Duration.ofHours(24);

	private static final String V1_HTML = "<html><body><p>Welcome to our privacy policy.</p>"
			+ "<p>This policy applies to all users of the service.</p></body></html>";
	private static final String V2_HTML = "<html><body><p>Welcome to our privacy policy.</p>"
			+ "<p>This policy applies to all users of the service.</p>"
			+ "<p>We share your location data with third-party advertising partners.</p></body></html>";
	private static final String V3_HTML = "<html><body><p>Welcome to our privacy policy.</p>"
			+ "<p>This policy applies to all users of the service.</p>"
			+ "<p>We share your location data with third-party advertising partners.</p>"
			+ "<p>We retain your account data for as long as your account is active.</p></body></html>";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyArchiveService archiveService;
	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private PolicyChangeRecordRepository changeRepository;
	@Autowired private ChangeConceptMatchRepository matchRepository;
	@Autowired private ChangeImpactRepository impactRepository;
	@Autowired private ImpactAssessmentRepository assessmentRepository;
	@Autowired private ImpactAssessmentBreakdownRepository breakdownRepository;
	@Autowired private RecommendationRepository recommendationRepository;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private PolicyFetchAttemptRepository attemptRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private PolicyObservationPersistenceService persistenceService;
	@Autowired private PolicyFetchAttemptService attemptService;
	@Autowired private NotificationFanOutService fanOutService;
	@Autowired private AuditVerificationService verificationService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
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
		userRepository.deleteAll();
	}

	@Test
	void archiveRetainsPopulatedHistory() {
		Fixture fixture = seedArchivedPolicyWithHistory();
		Policy before = policyRepository.findById(fixture.policyId()).orElseThrow();

		assertThat(before.getStatus()).isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(before.getName()).isEqualTo(fixture.name());
		assertThat(before.getUrl()).isEqualTo(fixture.url());
		assertThat(before.getOwner().getId()).isEqualTo(fixture.ownerId());
		assertThat(before.getNextCheckAt()).isEqualTo(fixture.nextCheckAt());
		assertThat(before.getCreatedAt()).isEqualTo(fixture.createdAt());
		assertThat(versionRepository.count()).isEqualTo(2);
		assertThat(changeRepository.count()).isPositive();
		assertThat(matchRepository.count()).isPositive();
		assertThat(impactRepository.count()).isPositive();
		assertThat(assessmentRepository.count()).isEqualTo(1);
		assertThat(breakdownRepository.count()).isPositive();
		assertThat(recommendationRepository.count()).isPositive();
		assertThat(notificationRepository.count()).isEqualTo(1);
		assertThat(attemptRepository.count()).isEqualTo(2);
		assertThat(auditEventRepository.count()).isEqualTo(1);
		assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);

		assertThat(versionRepository.findByPolicy_IdAndVersionNumber(
				fixture.policyId(), 1)).isPresent();
		UUID v2Id = versionRepository.findByPolicy_IdAndVersionNumber(
				fixture.policyId(), 2).orElseThrow().getId();
		assertThat(changeRepository.findAll()).isNotEmpty();
		UUID assessmentId = assessmentRepository.findAll().get(0).getId();
		assertThat(assessmentRepository.findAll().get(0).getNewVersion().getId())
				.isEqualTo(v2Id);
		assertThat(assessmentRepository.findByUser_IdAndNewVersion_Id(
				fixture.ownerId(), v2Id)).isPresent();
		assertThat(notificationRepository.findAll().get(0).getAssessment().getId())
				.isEqualTo(assessmentId);
	}

	@Test
	void schedulerIgnoresArchivedPolicy() {
		Fixture fixture = seedArchivedPolicyWithHistory();
		Policy archived = policyRepository.findById(fixture.policyId()).orElseThrow();
		archived.setNextCheckAt(NOW.minusSeconds(60));
		policyRepository.saveAndFlush(archived);

		scheduler(new MapFetcher(Map.of(fixture.url(), V3_HTML))).checkDuePolicies();

		assertThat(attemptRepository.count()).isEqualTo(2);
		assertThat(versionRepository.count()).isEqualTo(2);
		assertThat(notificationRepository.count()).isEqualTo(1);
		assertThat(policyRepository.findById(fixture.policyId()).orElseThrow()
				.getNextCheckAt()).isEqualTo(NOW.minusSeconds(60));
	}

	@Test
	void manualObservationOfArchivedPolicyCompletesWithSilentFanOut() {
		Fixture fixture = seedArchivedPolicyWithHistory();
		PolicyObservationService orchestrator = orchestrator(
				new MapFetcher(Map.of(fixture.url(), V3_HTML)));

		PolicyObservationResult result = orchestrator.observe(fixture.policyId(),
				PolicyFetchAttemptTrigger.MANUAL);

		assertThat(result.outcome())
				.isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(versionRepository.count()).isEqualTo(3);
		assertThat(attemptRepository.count()).isEqualTo(3);

		fanOutService.fanOut(fixture.policyId(), result);

		assertThat(assessmentRepository.count()).isEqualTo(1);
		assertThat(notificationRepository.count()).isEqualTo(1);
	}

	@Test
	void archiveDuringInflightObservationCompletesCleanly() throws Exception {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);
		LatchFetcher fetcher = new LatchFetcher(V1_HTML);
		PolicyObservationService orchestrator = orchestrator(fetcher);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<PolicyObservationResult> observation = pool.submit(() -> orchestrator
					.observe(policy.getId(), PolicyFetchAttemptTrigger.MANUAL));
			assertThat(fetcher.entered.await(30, TimeUnit.SECONDS)).isTrue();

			assertThat(archiveService.archive(owner.getId(), policy.getId())).isTrue();

			fetcher.release.countDown();
			PolicyObservationResult result = observation.get(60, TimeUnit.SECONDS);

			assertThat(result.outcome())
					.isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
			assertThat(policyRepository.findById(policy.getId()).orElseThrow()
					.getStatus()).isEqualTo(PolicyStatus.ARCHIVED);
			assertThat(versionRepository.count()).isEqualTo(1);
			assertThat(attemptRepository.count()).isEqualTo(1);

			fanOutService.fanOut(policy.getId(), result);

			assertThat(assessmentRepository.count()).isZero();
			assertThat(notificationRepository.count()).isZero();
			assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void auditChainRemainsValidAfterArchive() {
		Fixture fixture = seedArchivedPolicyWithHistory();

		AuditVerificationResult verdict = verificationService.verify();

		assertThat(verdict.isValid()).isTrue();
		assertThat(verdict.getVerifiedCount()).isEqualTo(1);
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT actor_user_id, resource_type, resource_id, metadata FROM audit_event "
						+ "WHERE event_type = 'POLICY_ARCHIVED'");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("actor_user_id")).isEqualTo(fixture.ownerId());
		assertThat(rows.get(0).get("resource_type")).isEqualTo("POLICY");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(fixture.policyId());
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");
	}

	private record Fixture(UUID policyId, UUID ownerId, String name, String url,
			Instant nextCheckAt, Instant createdAt) {
	}

	private Fixture seedArchivedPolicyWithHistory() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);
		MapFetcher fetcher = new MapFetcher(Map.of(policy.getUrl(), V1_HTML));
		PolicyObservationService orchestrator = orchestrator(fetcher);

		orchestrator.observe(policy.getId(), PolicyFetchAttemptTrigger.MANUAL);
		fetcher.put(policy.getUrl(), V2_HTML);
		PolicyObservationResult second = orchestrator.observe(policy.getId(),
				PolicyFetchAttemptTrigger.MANUAL);
		fanOutService.fanOut(policy.getId(), second);

		Policy seeded = policyRepository.findById(policy.getId()).orElseThrow();
		Fixture snapshot = new Fixture(seeded.getId(), owner.getId(), seeded.getName(),
				seeded.getUrl(), seeded.getNextCheckAt(), seeded.getCreatedAt());

		assertThat(archiveService.archive(owner.getId(), policy.getId())).isTrue();
		return snapshot;
	}

	private Policy ownedPolicy(User owner) {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setOwner(owner);
		return policyRepository.saveAndFlush(policy);
	}

	private PolicyObservationService orchestrator(PolicyFetcher fetcher) {
		return new PolicyObservationService(policyRepository, fetcher, extractor, normalizer,
				hasher, persistenceService, attemptService, new RetryPolicy(5,
						Duration.ofMinutes(5), 2.0, Duration.ofHours(6),
						Duration.ofHours(24), new Random()),
				transactionManager);
	}

	private PolicyObservationScheduler scheduler(PolicyFetcher fetcher) {
		return new PolicyObservationScheduler(policyRepository, orchestrator(fetcher),
				fanOutService, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC), INTERVAL);
	}

	private int countByType(String eventType) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ?", Integer.class, eventType);
		return count == null ? 0 : count;
	}

	private static final class MapFetcher implements PolicyFetcher {
		private final Map<String, String> routes = new ConcurrentHashMap<>();

		MapFetcher(Map<String, String> routes) {
			this.routes.putAll(routes);
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

	private static final class LatchFetcher implements PolicyFetcher {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final String html;

		LatchFetcher(String html) {
			this.html = html;
		}

		@Override
		public FetchResult fetch(String url) {
			entered.countDown();
			try {
				if (!release.await(30, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Fetch release timed out");
				}
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new PolicyFetchException("fetch interrupted");
			}
			return new FetchResult(url, 200, "text/html", html);
		}
	}
}
