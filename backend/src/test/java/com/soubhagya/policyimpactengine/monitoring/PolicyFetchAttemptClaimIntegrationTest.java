package com.soubhagya.policyimpactengine.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchClaimRejectedException;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyObservationScheduler;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.notification.application.NotificationFanOutService;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
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
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

/**
 * Phase 2U — Testcontainers concurrency tests for atomic work claiming.
 *
 * <p>Proves the PostgreSQL partial unique index (V11) plus the conditional
 * claim update serialize concurrent triggers for the same policy across
 * threads (and therefore across instances — no Java synchronization is
 * involved): exactly one trigger fetches, the loser is rejected (MANUAL)
 * or skipped (SCHEDULED) without fetching and without leaving a second
 * runnable row. No retry, backoff, jitter, or stale recovery is exercised
 * here; those belong to Phase 2U.1/2U.2.
 */
@SpringBootTest
@Testcontainers
class PolicyFetchAttemptClaimIntegrationTest {

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

	/**
	 * Requirement A — two concurrent SCHEDULED claims: exactly one wins,
	 * exactly one reaches the fetcher, the loser does not fetch.
	 */
	@Test
	void concurrentScheduledClaimsForSamePolicyExactlyOneFetches() throws Exception {
		Policy policy = registeredPolicy();

		RaceOutcome outcome = race(policy.getId(),
				PolicyFetchAttemptTrigger.SCHEDULED, PolicyFetchAttemptTrigger.SCHEDULED);

		assertThat(outcome.fetchCount()).isEqualTo(1);
		assertThat(outcome.winner().outcome())
				.isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(attempts.get(0).getTrigger())
				.isEqualTo(PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
	}

	/**
	 * Requirement B — two concurrent MANUAL claims: exactly one wins, the
	 * second is rejected, no duplicate fetch.
	 */
	@Test
	void concurrentManualClaimsForSamePolicyRejectSecondWithoutDuplicateFetch() throws Exception {
		Policy policy = registeredPolicy();

		RaceOutcome outcome = race(policy.getId(),
				PolicyFetchAttemptTrigger.MANUAL, PolicyFetchAttemptTrigger.MANUAL);

		assertThat(outcome.fetchCount()).isEqualTo(1);
		assertThat(outcome.winner().outcome())
				.isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(attempts.get(0).getTrigger()).isEqualTo(PolicyFetchAttemptTrigger.MANUAL);
	}

	/**
	 * Requirement C — MANUAL vs SCHEDULED race: exactly one wins, the other
	 * does not fetch.
	 */
	@Test
	void manualVersusScheduledRaceExactlyOneWins() throws Exception {
		Policy policy = registeredPolicy();

		RaceOutcome outcome = race(policy.getId(),
				PolicyFetchAttemptTrigger.MANUAL, PolicyFetchAttemptTrigger.SCHEDULED);

		assertThat(outcome.fetchCount()).isEqualTo(1);
		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(attempts.get(0).getTrigger()).isIn(
				PolicyFetchAttemptTrigger.MANUAL, PolicyFetchAttemptTrigger.SCHEDULED);
	}

	/**
	 * Requirement D — database partial unique invariant: two non-terminal
	 * attempts for the same policy cannot coexist; terminal rows stay
	 * append-only history and never block later checks.
	 */
	@Test
	void partialUniqueIndexRejectsSecondNonTerminalAttempt() {
		Policy policy = registeredPolicy();
		Instant now = Instant.now();
		PolicyFetchAttempt first = attemptRepository.saveAndFlush(
				PolicyFetchAttempt.pending(policy, PolicyFetchAttemptTrigger.MANUAL, 1, now));

		assertThatThrownBy(() -> attemptRepository.saveAndFlush(
				PolicyFetchAttempt.pending(policy, PolicyFetchAttemptTrigger.SCHEDULED, 1, now)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> attemptRepository.saveAndFlush(
				new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 1, now)))
				.isInstanceOf(DataIntegrityViolationException.class);

		// Terminal rows are history: completing the first row frees the
		// policy for the next check without any retry-chain semantics.
		attemptService.markSucceeded(first.getId(), 200, 8L);
		PolicyFetchAttempt next = attemptRepository.saveAndFlush(
				PolicyFetchAttempt.pending(policy, PolicyFetchAttemptTrigger.SCHEDULED, 1, now));
		assertThat(next.getStatus()).isEqualTo(PolicyFetchAttemptStatus.PENDING);
	}

	/**
	 * Requirement E — conditional claim: PENDING → IN_PROGRESS succeeds
	 * exactly once; a second claim of the same attempt affects zero rows,
	 * as does a claim of an unknown attempt.
	 */
	@Test
	void conditionalClaimSucceedsExactlyOnce() {
		Policy policy = registeredPolicy();
		Instant now = Instant.now();
		PolicyFetchAttempt pending = attemptRepository.saveAndFlush(
				PolicyFetchAttempt.pending(policy, PolicyFetchAttemptTrigger.MANUAL, 1, now));

		int won = attemptRepository.claimPendingAttempt(pending.getId(),
				PolicyFetchAttemptStatus.PENDING, PolicyFetchAttemptStatus.IN_PROGRESS, now);

		assertThat(won).isEqualTo(1);

		int replayed = attemptRepository.claimPendingAttempt(pending.getId(),
				PolicyFetchAttemptStatus.PENDING, PolicyFetchAttemptStatus.IN_PROGRESS, now);
		assertThat(replayed).isZero();

		int unknown = attemptRepository.claimPendingAttempt(UUID.randomUUID(),
				PolicyFetchAttemptStatus.PENDING, PolicyFetchAttemptStatus.IN_PROGRESS, now);
		assertThat(unknown).isZero();
	}

	/**
	 * Requirement F — terminal lifecycle remains: IN_PROGRESS → SUCCESS /
	 * FAILED / SKIPPED_UNCHANGED, and no terminal attempt can be claimed
	 * again.
	 */
	@Test
	void terminalLifecycleRemainsAndTerminalRowsCannotBeClaimed() {
		Instant now = Instant.now();

		Policy succeeded = registeredPolicy();
		PolicyFetchAttempt inProgress = attemptService.beginAttempt(
				succeeded, PolicyFetchAttemptTrigger.MANUAL);
		assertThat(inProgress.getStatus()).isEqualTo(PolicyFetchAttemptStatus.IN_PROGRESS);
		assertThat(attemptRepository.claimPendingAttempt(inProgress.getId(),
				PolicyFetchAttemptStatus.PENDING, PolicyFetchAttemptStatus.IN_PROGRESS, now))
				.isZero();
		attemptService.markSucceeded(inProgress.getId(), 200, 8L);
		assertThat(attemptRepository.claimPendingAttempt(inProgress.getId(),
				PolicyFetchAttemptStatus.PENDING, PolicyFetchAttemptStatus.IN_PROGRESS, now))
				.isZero();

		Policy failed = registeredPolicy();
		PolicyFetchAttempt failing = attemptService.beginAttempt(
				failed, PolicyFetchAttemptTrigger.SCHEDULED);
		attemptService.markFailed(failing.getId(), null, null, "connection refused",
				PolicyFetchFailureKind.TRANSIENT);
		assertThat(attemptRepository.claimPendingAttempt(failing.getId(),
				PolicyFetchAttemptStatus.PENDING, PolicyFetchAttemptStatus.IN_PROGRESS, now))
				.isZero();

		Policy skipped = registeredPolicy();
		PolicyFetchAttempt skipping = attemptService.beginAttempt(
				skipped, PolicyFetchAttemptTrigger.MANUAL);
		attemptService.markSkippedUnchanged(skipping.getId(), 200, 8L);
		assertThat(attemptRepository.claimPendingAttempt(skipping.getId(),
				PolicyFetchAttemptStatus.PENDING, PolicyFetchAttemptStatus.IN_PROGRESS, now))
				.isZero();

		assertThat(attemptRepository.findByPolicy_IdOrderByStartedAtDesc(succeeded.getId()))
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.SUCCESS);
		assertThatThrownBy(() -> attemptService.markFailed(
				inProgress.getId(), 200, 1L, "late", PolicyFetchFailureKind.TRANSIENT))
				.isInstanceOf(IllegalStateException.class);
	}

	/**
	 * Requirement G — concurrent claims for different policies proceed
	 * independently.
	 */
	@Test
	void concurrentClaimsForDifferentPoliciesProceedIndependently() throws Exception {
		Policy first = registeredPolicy();
		Policy second = registeredPolicy();
		AtomicInteger fetchCount = new AtomicInteger();
		PolicyObservationService firstOrchestrator = orchestrator(countingFetcher(fetchCount,
				"<html><body><p>First policy.</p></body></html>"));
		PolicyObservationService secondOrchestrator = orchestrator(countingFetcher(fetchCount,
				"<html><body><p>Second policy.</p></body></html>"));

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
			assertThat(fetchCount).hasValue(2);
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

	/**
	 * SCHEDULED collision at the tick level: a due policy already owned by
	 * another trigger is skipped for this cycle — no fetch, no new attempt
	 * row — while its next check still advances uniformly.
	 */
	@Test
	void schedulerSkipsDuePolicyOwnedByAnotherTrigger() {
		Instant tickStart = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		Policy due = duePolicy(tickStart.minusSeconds(60));
		PolicyFetchAttempt inFlight = attemptService.beginAttempt(
				due, PolicyFetchAttemptTrigger.MANUAL);
		AtomicInteger fetchCount = new AtomicInteger();
		PolicyObservationService orchestrator = orchestrator(
				countingFetcher(fetchCount, "<html><body><p>Never fetched.</p></body></html>"));
		PolicyObservationScheduler scheduler = new PolicyObservationScheduler(policyRepository,
				orchestrator, mock(NotificationFanOutService.class), transactionManager,
				Clock.fixed(tickStart, ZoneOffset.UTC), INTERVAL);

		scheduler.checkDuePolicies();

		assertThat(fetchCount).hasValue(0);
		assertThat(attemptRepository.count()).isEqualTo(1);
		assertThat(attemptRepository.findById(inFlight.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyFetchAttemptStatus.IN_PROGRESS);
		assertThat(reload(due).getNextCheckAt()).isEqualTo(tickStart.plus(INTERVAL));
	}

	/**
	 * Runs two concurrent observations of one policy with a fetcher that
	 * blocks the winner until the loser has been rejected, so the overlap
	 * is deterministic rather than timing-dependent.
	 */
	private RaceOutcome race(UUID policyId, PolicyFetchAttemptTrigger firstTrigger,
			PolicyFetchAttemptTrigger secondTrigger) throws Exception {
		AtomicInteger fetchCount = new AtomicInteger();
		CountDownLatch enteredFetch = new CountDownLatch(1);
		CountDownLatch releaseFetch = new CountDownLatch(1);
		PolicyFetcher blockingFetcher = url -> {
			fetchCount.incrementAndGet();
			enteredFetch.countDown();
			try {
				if (!releaseFetch.await(60, TimeUnit.SECONDS)) {
					throw new IllegalStateException("fetch release timed out");
				}
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("fetch interrupted", interrupted);
			}
			return new FetchResult(url, 200, "text/html",
					"<html><body><p>Claimed content.</p></body></html>");
		};
		PolicyObservationService orchestrator = orchestrator(blockingFetcher);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<PolicyObservationResult> f1 = executor.submit(() -> {
				start.await();
				return orchestrator.observe(policyId, firstTrigger);
			});
			Future<PolicyObservationResult> f2 = executor.submit(() -> {
				start.await();
				return orchestrator.observe(policyId, secondTrigger);
			});
			start.countDown();

			assertThat(enteredFetch.await(60, TimeUnit.SECONDS))
					.as("exactly one trigger reaches the fetch").isTrue();
			Future<PolicyObservationResult> loser = waitForFirstDone(f1, f2);
			assertThatThrownBy(() -> loser.get(60, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(PolicyFetchClaimRejectedException.class);
			releaseFetch.countDown();

			Future<PolicyObservationResult> winner = (loser == f1) ? f2 : f1;
			return new RaceOutcome(winner.get(60, TimeUnit.SECONDS), fetchCount.get());
		}
		finally {
			releaseFetch.countDown();
			executor.shutdownNow();
		}
	}

	private Future<PolicyObservationResult> waitForFirstDone(
			Future<PolicyObservationResult> f1, Future<PolicyObservationResult> f2)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (System.nanoTime() < deadline) {
			if (f1.isDone()) {
				return f1;
			}
			if (f2.isDone()) {
				return f2;
			}
			Thread.sleep(25);
		}
		throw new IllegalStateException("neither concurrent trigger finished within 60s");
	}

	private PolicyObservationService orchestrator(PolicyFetcher fetcher) {
		return new PolicyObservationService(policyRepository, fetcher, extractor, normalizer,
				hasher, persistenceService, attemptService, testRetryPolicy(), transactionManager);
	}

	private RetryPolicy testRetryPolicy() {
		return new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random());
	}

	private PolicyFetcher countingFetcher(AtomicInteger fetchCount, String html) {
		return url -> {
			fetchCount.incrementAndGet();
			return new FetchResult(url, 200, "text/html", html);
		};
	}

	private List<PolicyFetchAttempt> attemptsOf(Policy policy) {
		return attemptRepository.findByPolicy_IdOrderByStartedAtDesc(policy.getId());
	}

	private Policy registeredPolicy() {
		return policyRepository.saveAndFlush(
				new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
	}

	private Policy duePolicy(Instant nextCheckAt) {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setNextCheckAt(nextCheckAt);
		return policyRepository.saveAndFlush(policy);
	}

	private Policy reload(Policy policy) {
		return policyRepository.findById(policy.getId()).orElseThrow();
	}

	private record RaceOutcome(PolicyObservationResult winner, int fetchCount) {
	}
}
