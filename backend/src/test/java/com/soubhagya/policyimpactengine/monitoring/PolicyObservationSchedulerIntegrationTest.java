package com.soubhagya.policyimpactengine.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyObservationScheduler;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.notification.application.NotificationFanOutService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome;
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
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;

/**
 * Phase 2T — Testcontainers integration tests for the scheduled tick
 * through the real observation pipeline: due selection, SCHEDULED
 * trigger recording, uniform next-check advancement (including failures),
 * failure isolation across policies, and single observation per tick.
 */
@SpringBootTest
@Testcontainers
class PolicyObservationSchedulerIntegrationTest {

	private static final Instant TICK_START = Instant.parse("2026-09-18T10:00:00Z");
	private static final Duration INTERVAL = Duration.ofHours(24);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private PolicyChangeRecordRepository changeRepository;
	@Autowired private ChangeConceptMatchRepository matchRepository;
	@Autowired private ChangeImpactRepository impactRepository;
	@Autowired private PolicyFetchAttemptRepository attemptRepository;
	@Autowired private PolicyFetchAttemptService attemptService;
	@Autowired private PolicyObservationPersistenceService persistenceService;
	@Autowired private ImpactAssessmentService assessmentService;
	@Autowired private RecommendationService recommendationService;
	@Autowired private PlatformTransactionManager transactionManager;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void clean() {
		attemptRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void tickObservesDuePoliciesRecordsScheduledAndAdvances() {
		Policy due = policyAt(PolicyStatus.ACTIVE, TICK_START.minusSeconds(60));
		Policy future = policyAt(PolicyStatus.ACTIVE, TICK_START.plusSeconds(3600));
		Policy archived = policyAt(PolicyStatus.ARCHIVED, TICK_START.minusSeconds(60));
		renameUrl(due, "https://example.com/due");
		PolicyObservationScheduler scheduler = scheduler(routingFetcher(
				Map.of("due", "<html><body><p>Due content.</p></body></html>")));

		scheduler.checkDuePolicies();

		assertThat(versionRepository.count()).isEqualTo(1);
		List<PolicyFetchAttempt> attempts = attemptRepository.findAll();
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getPolicy().getId()).isEqualTo(due.getId());
		assertThat(attempts.get(0).getTrigger()).isEqualTo(PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(attempts.get(0).getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(reload(due).getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
		assertThat(reload(future).getNextCheckAt()).isEqualTo(TICK_START.plusSeconds(3600));
		assertThat(reload(archived).getNextCheckAt()).isEqualTo(TICK_START.minusSeconds(60));
		assertThat(versionRepository.count()).isEqualTo(1);
	}

	@Test
	void failedPolicyAdvancesAndDoesNotStopRemainingPolicies() {
		Policy failing = policyAt(PolicyStatus.ACTIVE, TICK_START.minusSeconds(120));
		Policy healthy = policyAt(PolicyStatus.ACTIVE, TICK_START.minusSeconds(60));
		PolicyObservationScheduler scheduler = scheduler(url -> {
			if (url.endsWith("/healthy")) {
				return new FetchResult(url, 200, "text/html",
						"<html><body><p>Healthy.</p></body></html>");
			}
			throw new PolicyFetchException("connection refused");
		});
		renameUrl(failing, "https://example.com/failing");
		renameUrl(healthy, "https://example.com/healthy");

		scheduler.checkDuePolicies();

		List<PolicyFetchAttempt> attempts = attemptRepository.findAll();
		assertThat(attempts).hasSize(2);
		assertThat(attempts)
				.extracting(a -> a.getPolicy().getId())
				.containsExactlyInAnyOrder(failing.getId(), healthy.getId());
		assertThat(attempts)
				.filteredOn(a -> a.getPolicy().getId().equals(failing.getId()))
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.FAILED);
		assertThat(attempts)
				.filteredOn(a -> a.getPolicy().getId().equals(healthy.getId()))
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.SUCCESS);
		// Uniform advancement applies to the failed policy as well, but a
		// failure reschedules from its own failure time (Phase 2U.1), not
		// from the tick start — the legacy fixture failure is permanent,
		// so it resumes the regular interval from when it failed, which is
		// after the frozen tick start.
		assertThat(reload(failing).getNextCheckAt()).isAfter(TICK_START.plus(INTERVAL));
		assertThat(reload(healthy).getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL));
		assertThat(versionRepository.count()).isEqualTo(1);
	}

	@Test
	void unchangedPolicyAdvancesNextCheck() {
		Policy policy = policyAt(PolicyStatus.ACTIVE, TICK_START.minusSeconds(60));
		renameUrl(policy, "https://example.com/steady");
		ManualClock clock = new ManualClock(TICK_START);
		PolicyObservationScheduler scheduler = scheduler(
				routingFetcher(Map.of("steady", "<html><body><p>Steady.</p></body></html>")),
				clock);

		scheduler.checkDuePolicies();
		clock.advance(INTERVAL);
		scheduler.checkDuePolicies();

		assertThat(versionRepository.count()).isEqualTo(1);
		assertThat(attemptRepository.findAll())
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.SUCCESS, PolicyFetchAttemptStatus.SKIPPED_UNCHANGED);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(TICK_START.plus(INTERVAL).plus(INTERVAL));
	}

	@Test
	void advancedPoliciesAreNotReobservedWithoutTimePassing() {
		Policy policy = policyAt(PolicyStatus.ACTIVE, TICK_START.minusSeconds(60));
		PolicyObservationScheduler scheduler = scheduler(
				routingFetcher(Map.of("once", "<html><body><p>Once.</p></body></html>")));
		renameUrl(policy, "https://example.com/once");

		scheduler.checkDuePolicies();
		scheduler.checkDuePolicies();

		// First tick observed and advanced past the frozen tick start, so
		// the second tick finds nothing due.
		assertThat(attemptRepository.count()).isEqualTo(1);
		assertThat(versionRepository.count()).isEqualTo(1);
	}

	private PolicyObservationScheduler scheduler(PolicyFetcher fetcher) {
		return scheduler(fetcher, Clock.fixed(TICK_START, ZoneOffset.UTC));
	}

	private PolicyObservationScheduler scheduler(PolicyFetcher fetcher, Clock clock) {
		PolicyObservationService orchestrator = new PolicyObservationService(policyRepository,
				fetcher, extractor, normalizer, hasher, persistenceService, attemptService,
				testRetryPolicy(), transactionManager);
		NotificationFanOutService fanOut = new NotificationFanOutService(policyRepository,
				versionRepository, assessmentService, recommendationService);
		return new PolicyObservationScheduler(policyRepository, orchestrator, fanOut,
				transactionManager, clock, INTERVAL);
	}

	private RetryPolicy testRetryPolicy() {
		return new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random());
	}

	private PolicyFetcher routingFetcher(Map<String, String> pathToHtml) {
		return url -> {
			String html = pathToHtml.get(url.substring(url.lastIndexOf('/') + 1));
			if (html == null) {
				throw new PolicyFetchException("no fixture for " + url);
			}
			return new FetchResult(url, 200, "text/html", html);
		};
	}

	private Policy policyAt(PolicyStatus status, Instant nextCheckAt) {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setStatus(status);
		policy.setNextCheckAt(nextCheckAt);
		return policyRepository.saveAndFlush(policy);
	}

	private void renameUrl(Policy policy, String url) {
		Policy reloaded = policyRepository.findById(policy.getId()).orElseThrow();
		reloaded.setUrl(url);
		policyRepository.saveAndFlush(reloaded);
	}

	private Policy reload(Policy policy) {
		return policyRepository.findById(policy.getId()).orElseThrow();
	}

	/** Deterministic manually advanced clock. */
	private static final class ManualClock extends Clock {

		private Instant now;

		ManualClock(Instant now) {
			this.now = now;
		}

		void advance(java.time.Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}
}
