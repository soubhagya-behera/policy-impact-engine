package com.soubhagya.policyimpactengine.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
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

/**
 * Phase 2S — Testcontainers integration tests: attempt recording through
 * the real observation pipeline (outcome mapping, failure rollback with
 * surviving FAILED rows, no transaction across the fetch, history
 * ordering, and concurrent observations).
 */
@SpringBootTest
@Testcontainers
class PolicyFetchAttemptIntegrationTest {

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
	void firstObservationRecordsSucceededAttempt() {
		Policy policy = registeredPolicy();
		String html = "<html><body><p>We collect email.</p></body></html>";
		PolicyObservationService orchestrator = orchestrator(htmlFetcher(html));

		PolicyObservationResult result = orchestrator.observe(policy.getId());

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(1);
		PolicyFetchAttempt attempt = attempts.get(0);
		assertThat(attempt.getPolicy().getId()).isEqualTo(policy.getId());
		assertThat(attempt.getTrigger()).isEqualTo(PolicyFetchAttemptTrigger.MANUAL);
		assertThat(attempt.getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(attempt.getAttemptNumber()).isEqualTo(1);
		assertThat(attempt.getHttpStatus()).isEqualTo(200);
		assertThat(attempt.getBytesFetched()).isEqualTo(html.getBytes(StandardCharsets.UTF_8).length);
		assertThat(attempt.getStartedAt()).isNotNull();
		assertThat(attempt.getCompletedAt()).isNotNull();
		assertThat(attempt.getCompletedAt()).isAfterOrEqualTo(attempt.getStartedAt());
		assertThat(attempt.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
	}

	@Test
	void unchangedObservationRecordsSkippedAttemptWithoutNewVersion() {
		Policy policy = registeredPolicy();
		PolicyObservationService orchestrator = orchestrator(htmlFetcher("<html><body><p>Same.</p></body></html>"));

		PolicyObservationResult first = orchestrator.observe(policy.getId());
		PolicyObservationResult second = orchestrator.observe(policy.getId());

		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(versionRepository.count()).isEqualTo(1);
		assertThat(attemptsOf(policy))
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.SKIPPED_UNCHANGED,
						PolicyFetchAttemptStatus.SUCCESS);
	}

	@Test
	void changedObservationRecordsSucceededAttemptWithNewVersion() {
		Policy policy = registeredPolicy();
		orchestrator(htmlFetcher("<html><body><p>Version one.</p></body></html>")).observe(policy.getId());
		PolicyObservationResult changed = orchestrator(
				htmlFetcher("<html><body><p>Version two with new wording.</p></body></html>"))
				.observe(policy.getId());

		assertThat(changed.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(versionRepository.count()).isEqualTo(2);
		assertThat(attemptsOf(policy))
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.SUCCESS, PolicyFetchAttemptStatus.SUCCESS);
	}

	@Test
	void fetchFailureRecordsFailedAttemptWithoutVersion() {
		Policy policy = registeredPolicy();
		PolicyFetcher failingFetcher = url -> {
			throw new PolicyFetchException("connection refused");
		};
		PolicyObservationService orchestrator = orchestrator(failingFetcher);

		assertThatThrownBy(() -> orchestrator.observe(policy.getId()))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("connection refused");

		assertThat(versionRepository.count()).isZero();
		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(1);
		PolicyFetchAttempt attempt = attempts.get(0);
		assertThat(attempt.getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(attempt.getErrorMessage()).contains("connection refused");
		assertThat(attempt.getHttpStatus()).isNull();
		assertThat(attempt.getBytesFetched()).isNull();
		assertThat(attempt.getCompletedAt()).isNotNull();
	}

	@Test
	void persistenceFailureRecordsFailedAttemptWithVersionRollback() {
		Policy policy = registeredPolicy();
		PolicyObservationPersistenceService failingPersistence =
				mock(PolicyObservationPersistenceService.class);
		when(failingPersistence.store(any(UUID.class), anyString(), anyString()))
				.thenThrow(new IllegalStateException("version store down"));
		PolicyObservationService orchestrator = new PolicyObservationService(policyRepository,
				htmlFetcher("<html><body><p>Content.</p></body></html>"), extractor, normalizer,
				hasher, failingPersistence, attemptService, testRetryPolicy(), transactionManager);

		assertThatThrownBy(() -> orchestrator.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("version store down");

		// The version transaction rolled back, but its FAILED attempt row survives.
		assertThat(versionRepository.count()).isZero();
		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(attempts.get(0).getErrorMessage()).contains("version store down");
		assertThat(attempts.get(0).getHttpStatus()).isEqualTo(200);
	}

	@Test
	void noDatabaseTransactionSpansTheHttpFetch() {
		Policy policy = registeredPolicy();
		AtomicBoolean txActiveDuringFetch = new AtomicBoolean(true);
		PolicyFetcher probingFetcher = url -> {
			txActiveDuringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
			return new FetchResult(url, 200, "text/html", "<html><body><p>Probed.</p></body></html>");
		};

		orchestrator(probingFetcher).observe(policy.getId());

		assertThat(txActiveDuringFetch).isFalse();
		assertThat(attemptsOf(policy))
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.SUCCESS);
	}

	@Test
	void attemptHistoryIsOrderedNewestFirst() {
		Policy policy = registeredPolicy();
		orchestrator(htmlFetcher("<html><body><p>Alpha.</p></body></html>")).observe(policy.getId());
		orchestrator(htmlFetcher("<html><body><p>Alpha.</p></body></html>")).observe(policy.getId());
		orchestrator(htmlFetcher("<html><body><p>Beta changed.</p></body></html>")).observe(policy.getId());

		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(3);
		assertThat(attempts)
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.SUCCESS,
						PolicyFetchAttemptStatus.SKIPPED_UNCHANGED,
						PolicyFetchAttemptStatus.SUCCESS);
		assertThat(attempts.get(0).getStartedAt())
				.isAfterOrEqualTo(attempts.get(1).getStartedAt());
		assertThat(attempts.get(1).getStartedAt())
				.isAfterOrEqualTo(attempts.get(2).getStartedAt());
	}

	@Test
	void concurrentObservationsCreateSeparateAttemptRows() throws Exception {
		Policy first = registeredPolicy();
		Policy second = registeredPolicy();
		PolicyObservationService firstOrchestrator =
				orchestrator(htmlFetcher("<html><body><p>First policy.</p></body></html>"));
		PolicyObservationService secondOrchestrator =
				orchestrator(htmlFetcher("<html><body><p>Second policy.</p></body></html>"));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<PolicyObservationResult> f1 = executor.submit(() -> {
				start.await();
				return firstOrchestrator.observe(first.getId());
			});
			Future<PolicyObservationResult> f2 = executor.submit(() -> {
				start.await();
				return secondOrchestrator.observe(second.getId());
			});
			start.countDown();
			assertThat(f1.get(60, TimeUnit.SECONDS).outcome())
					.isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
			assertThat(f2.get(60, TimeUnit.SECONDS).outcome())
					.isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
			assertThat(attemptRepository.count()).isEqualTo(2);
			assertThat(attemptsOf(first))
					.extracting(PolicyFetchAttempt::getStatus)
					.containsExactly(PolicyFetchAttemptStatus.SUCCESS);
			assertThat(attemptsOf(second))
					.extracting(PolicyFetchAttempt::getStatus)
					.containsExactly(PolicyFetchAttemptStatus.SUCCESS);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private PolicyObservationService orchestrator(PolicyFetcher fetcher) {
		return new PolicyObservationService(policyRepository, fetcher, extractor, normalizer,
				hasher, persistenceService, attemptService, testRetryPolicy(), transactionManager);
	}

	private RetryPolicy testRetryPolicy() {
		return new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random());
	}

	private PolicyFetcher htmlFetcher(String html) {
		return url -> new FetchResult(url, 200, "text/html", html);
	}

	private List<PolicyFetchAttempt> attemptsOf(Policy policy) {
		return attemptRepository.findByPolicy_IdOrderByStartedAtDesc(policy.getId());
	}

	private Policy registeredPolicy() {
		return policyRepository.saveAndFlush(
				new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
	}
}
