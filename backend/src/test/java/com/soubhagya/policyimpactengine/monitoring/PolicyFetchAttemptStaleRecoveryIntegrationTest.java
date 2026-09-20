package com.soubhagya.policyimpactengine.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.soubhagya.policyimpactengine.monitoring.application.StaleAttemptRecovery;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
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
 * Phase 2U.2 — Testcontainers stale-recovery tests over real PostgreSQL.
 *
 * <p>Proves the lease design end to end: an {@code IN_PROGRESS} row whose
 * {@code started_at} is at or past the stale timeout is transitioned in
 * place to {@code FAILED}/{@code TRANSIENT} (V11 slot freed, retry chain
 * continued through the unchanged Phase 2U.1 backoff), while fresh rows,
 * {@code PENDING} rows, and terminal rows are never touched. A frozen
 * manually advanced clock plus seeded retry policies make every timestamp
 * and backoff exact; threads stand in for separate application instances
 * because no Java synchronization is involved anywhere.
 */
@SpringBootTest
@Testcontainers
class PolicyFetchAttemptStaleRecoveryIntegrationTest {

	private static final Instant START = Instant.parse("2026-09-18T10:00:00Z");
	private static final Duration TIMEOUT = Duration.ofMinutes(30);
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
	@Autowired private PolicyObservationPersistenceService persistenceService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

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
	void staleRowRecoveredFreshRowUntouched() {
		Fixture fixture = fixture(5, 100);
		Policy stalePolicy = registeredPolicy(START.minusSeconds(60));
		Policy freshPolicy = registeredPolicy(START.minusSeconds(60));
		PolicyFetchAttempt stale = fixture.attemptService().beginAttempt(
				reload(stalePolicy), PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(Duration.ofMinutes(31));
		PolicyFetchAttempt fresh = fixture.attemptService().beginAttempt(
				reload(freshPolicy), PolicyFetchAttemptTrigger.SCHEDULED);
		Instant recoveredAt = fixture.clock().instant();
		Duration expectedBackoff = fixture.control().backoffDelay(1);

		int recovered = fixture.recovery().recoverStaleAttempts(recoveredAt);

		assertThat(recovered).isEqualTo(1);
		PolicyFetchAttempt reaped = attemptRepository.findById(stale.getId()).orElseThrow();
		assertThat(reaped.getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(reaped.getFailureKind()).isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		assertThat(reaped.getErrorMessage()).startsWith("Stale IN_PROGRESS");
		assertThat(reaped.getCompletedAt()).isEqualTo(recoveredAt);
		assertThat(reaped.getDurationMs())
				.isEqualTo(Duration.between(stale.getStartedAt(), recoveredAt).toMillis());
		assertThat(reaped.getDurationMs()).isGreaterThanOrEqualTo(0L);
		assertThat(reaped.getAttemptNumber()).isEqualTo(1);
		assertThat(reaped.getHttpStatus()).isNull();
		assertThat(reaped.getBytesFetched()).isNull();
		assertThat(reload(stalePolicy).getNextCheckAt())
				.isEqualTo(recoveredAt.plus(expectedBackoff));
		// The fresh row is untouched and its policy unscheduled.
		assertThat(attemptRepository.findById(fresh.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyFetchAttemptStatus.IN_PROGRESS);
		assertThat(reload(freshPolicy).getNextCheckAt()).isEqualTo(START.minusSeconds(60));
	}

	@Test
	void exactBoundaryIsStaleJustBeforeIsNot() {
		Fixture fixture = fixture(5, 100);
		Policy exactPolicy = registeredPolicy(START.minusSeconds(60));
		fixture.attemptService().beginAttempt(reload(exactPolicy),
				PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(TIMEOUT);

		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(1);

		Policy freshPolicy = registeredPolicy(fixture.clock().instant());
		fixture.attemptService().beginAttempt(reload(freshPolicy),
				PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(TIMEOUT.minusMillis(1));

		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(0);
		assertThat(attemptRepository.findAll())
				.filteredOn(a -> a.getPolicy().getId().equals(freshPolicy.getId()))
				.extracting(PolicyFetchAttempt::getStatus)
				.containsExactly(PolicyFetchAttemptStatus.IN_PROGRESS);
	}

	@Test
	void batchLimitReapsOldestFirstAcrossMultiplePolicies() {
		Fixture fixture = fixture(5, 2);
		Policy oldest = registeredPolicy(START.minusSeconds(60));
		fixture.attemptService().beginAttempt(reload(oldest), PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(Duration.ofSeconds(1));
		Policy middle = registeredPolicy(fixture.clock().instant());
		fixture.attemptService().beginAttempt(reload(middle), PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(Duration.ofSeconds(1));
		Policy newest = registeredPolicy(fixture.clock().instant());
		fixture.attemptService().beginAttempt(reload(newest), PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(Duration.ofMinutes(31));

		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(2);
		assertThat(statusOf(oldest)).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(statusOf(middle)).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(statusOf(newest)).isEqualTo(PolicyFetchAttemptStatus.IN_PROGRESS);

		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(1);
		assertThat(statusOf(newest)).isEqualTo(PolicyFetchAttemptStatus.FAILED);
	}

	@Test
	void v11SlotFreedAndReclaimContinuesChain() {
		Fixture fixture = fixture(5, 100);
		Policy policy = registeredPolicy(START.minusSeconds(60));
		fixture.attemptService().beginAttempt(reload(policy), PolicyFetchAttemptTrigger.MANUAL);
		// While the slot is held, both triggers are rejected.
		assertThatThrownBy(() -> fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.MANUAL))
				.isInstanceOf(PolicyFetchClaimRejectedException.class);
		assertThatThrownBy(() -> fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED))
				.isInstanceOf(PolicyFetchClaimRejectedException.class);

		fixture.clock().advance(Duration.ofMinutes(31));
		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(1);

		// The slot is free: the immediate re-claim continues the chain.
		PolicyFetchAttempt retry = fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.MANUAL);
		assertThat(retry.getAttemptNumber()).isEqualTo(2);
		assertThatThrownBy(() -> fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED))
				.isInstanceOf(PolicyFetchClaimRejectedException.class);
		fixture.attemptService().markFailed(retry.getId(), null, null, "cleanup",
				PolicyFetchFailureKind.TRANSIENT);
	}

	@Test
	void recoveredChainBacksOffThenExhaustsToInterval() {
		Fixture fixture = fixture(2, 100);
		Policy policy = registeredPolicy(START.minusSeconds(60));
		fixture.attemptService().beginAttempt(reload(policy), PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(Duration.ofMinutes(31));
		Duration first = fixture.control().backoffDelay(1);

		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(1);
		Instant firstRecoveryAt = fixture.clock().instant();
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(firstRecoveryAt.plus(first));

		fixture.clock().advance(Duration.ofSeconds(1));
		PolicyFetchAttempt second = fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(second.getAttemptNumber()).isEqualTo(2);
		fixture.clock().advance(Duration.ofMinutes(31));

		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(1);
		Instant exhaustedAt = fixture.clock().instant();
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(exhaustedAt.plus(INTERVAL));

		// The exhausted chain resets: the next check starts fresh at 1.
		fixture.clock().advance(Duration.ofSeconds(1));
		assertThat(fixture.attemptService().beginAttempt(reload(policy),
				PolicyFetchAttemptTrigger.SCHEDULED).getAttemptNumber()).isEqualTo(1);
	}

	@Test
	void permanentFailureChainAndLegacyNullKindAreUnaffected() {
		Fixture fixture = fixture(5, 100);
		Policy permanentPolicy = registeredPolicy(START.minusSeconds(60));
		assertThatThrownBy(() -> orchestrator(fixture, permanentFetcher())
				.observe(permanentPolicy.getId()))
				.isInstanceOf(PolicyFetchException.class);
		Policy legacyPolicy = registeredPolicy(START.minusSeconds(60));
		jdbcTemplate.update(
				"INSERT INTO policy_fetch_attempt (id, policy_id, trigger, status, attempt_number, "
						+ "started_at, completed_at, failure_kind) VALUES (?, ?, 'MANUAL', 'FAILED', 7, "
						+ "TIMESTAMP '2026-09-18 09:00:00+00', TIMESTAMP '2026-09-18 09:00:01+00', NULL)",
				UUID.randomUUID(), legacyPolicy.getId());

		// Separate the retry claims from the original failures by one
		// second so newest-first history ordering stays deterministic.
		fixture.clock().advance(Duration.ofSeconds(1));
		// Both histories reset the chain: the next claim starts at 1 and,
		// once orphaned, is recovered as TRANSIENT without touching the
		// original rows.
		PolicyFetchAttempt permanentRetry = fixture.attemptService().beginAttempt(
				reload(permanentPolicy), PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(permanentRetry.getAttemptNumber()).isEqualTo(1);
		PolicyFetchAttempt legacyRetry = fixture.attemptService().beginAttempt(
				reload(legacyPolicy), PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(legacyRetry.getAttemptNumber()).isEqualTo(1);

		fixture.clock().advance(Duration.ofMinutes(31));
		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(2);

		List<PolicyFetchAttempt> permanentHistory = attemptsOf(permanentPolicy);
		assertThat(permanentHistory).hasSize(2);
		assertThat(permanentHistory.get(1).getFailureKind())
				.isEqualTo(PolicyFetchFailureKind.PERMANENT);
		assertThat(permanentHistory.get(0).getFailureKind())
				.isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		assertThat(permanentHistory.get(0).getErrorMessage()).startsWith("Stale IN_PROGRESS");
		List<PolicyFetchAttempt> legacyHistory = attemptsOf(legacyPolicy);
		assertThat(legacyHistory).hasSize(2);
		assertThat(legacyHistory.get(1).getFailureKind()).isNull();
		assertThat(legacyHistory.get(1).getAttemptNumber()).isEqualTo(7);
		assertThat(legacyHistory.get(0).getFailureKind())
				.isEqualTo(PolicyFetchFailureKind.TRANSIENT);

		fixture.clock().advance(Duration.ofSeconds(1));
		assertThat(fixture.attemptService().beginAttempt(reload(permanentPolicy),
				PolicyFetchAttemptTrigger.SCHEDULED).getAttemptNumber()).isEqualTo(2);
		assertThat(fixture.attemptService().beginAttempt(reload(legacyPolicy),
				PolicyFetchAttemptTrigger.SCHEDULED).getAttemptNumber()).isEqualTo(2);
	}

	@Test
	void schedulerSkipsClaimedPolicyThenObservesAfterRecoveryBackoff() {
		Fixture fixture = fixture(5, 100);
		Policy policy = registeredPolicy(START.minusSeconds(60));
		renameUrl(policy, "https://example.com/recovered");
		fixture.attemptService().beginAttempt(reload(policy), PolicyFetchAttemptTrigger.MANUAL);
		fixture.clock().advance(Duration.ofMinutes(31));
		CountingFetcher fetcher = new CountingFetcher(url ->
				new FetchResult(url, 200, "text/html", "<html><body><p>Recovered.</p></body></html>"));
		PolicyObservationScheduler scheduler = scheduler(fixture, fetcher);

		// The slot is still held: the tick skips without fetching.
		scheduler.checkDuePolicies();
		assertThat(fetcher.invocations()).isEqualTo(0);

		// Recovery frees the slot and reschedules on backoff, so the next
		// tick still finds nothing due and preserves the backoff.
		Instant recoveredAt = fixture.clock().instant();
		Duration backoff = fixture.control().backoffDelay(1);
		assertThat(fixture.recovery().recoverStaleAttempts(recoveredAt)).isEqualTo(1);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(recoveredAt.plus(backoff));
		scheduler.checkDuePolicies();
		assertThat(fetcher.invocations()).isEqualTo(0);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(recoveredAt.plus(backoff));

		// Past the backoff the policy is due again and the retry observes
		// as attempt 2 through the normal pipeline.
		fixture.clock().advance(Duration.ofHours(1));
		Instant tickStart = fixture.clock().instant();
		scheduler.checkDuePolicies();
		assertThat(fetcher.invocations()).isEqualTo(1);
		List<PolicyFetchAttempt> history = attemptsOf(policy);
		assertThat(history).hasSize(2);
		assertThat(history.get(0).getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(history.get(0).getAttemptNumber()).isEqualTo(2);
		assertThat(history.get(0).getTrigger())
				.isEqualTo(PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(history.get(1).getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(tickStart.plus(INTERVAL));
		assertThat(versionRepository.count()).isEqualTo(1);
	}

	@Test
	void twoRecoveryWorkersRacingOnOneStaleRowExactlyOneWins() throws Exception {
		Fixture fixture = fixture(5, 100);
		Policy policy = registeredPolicy(START.minusSeconds(60));
		PolicyFetchAttempt stale = fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(Duration.ofMinutes(31));
		Instant now = fixture.clock().instant();
		StaleAttemptRecovery workerA = new StaleAttemptRecovery(attemptRepository,
				policyRepository, transactionManager, Clock.fixed(now, ZoneOffset.UTC),
				fixture.retry(), TIMEOUT, 100);
		StaleAttemptRecovery workerB = new StaleAttemptRecovery(attemptRepository,
				policyRepository, transactionManager, Clock.fixed(now, ZoneOffset.UTC),
				fixture.retry(), TIMEOUT, 100);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<Integer> first = pool.submit(() -> {
				start.await();
				return workerA.recoverStaleAttempts(now);
			});
			Future<Integer> second = pool.submit(() -> {
				start.await();
				return workerB.recoverStaleAttempts(now);
			});
			start.countDown();
			int total = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);

			// Exactly one worker won: one FAILED row, one reschedule, and
			// the single jitter draw matches the control's first draw (a
			// loser reschedule would have consumed a second draw).
			assertThat(total).isEqualTo(1);
			assertThat(attemptRepository.findAll()).hasSize(1);
			PolicyFetchAttempt reaped = attemptRepository.findById(stale.getId()).orElseThrow();
			assertThat(reaped.getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
			assertThat(reaped.getFailureKind()).isEqualTo(PolicyFetchFailureKind.TRANSIENT);
			assertThat(reaped.getCompletedAt()).isEqualTo(now);
			assertThat(reload(policy).getNextCheckAt())
					.isEqualTo(now.plus(fixture.control().backoffDelay(1)));
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void recoveryLosesWhenWorkerCompletesFirst() {
		Fixture fixture = fixture(5, 100);
		Policy policy = registeredPolicy(START.minusSeconds(60));
		PolicyFetchAttempt stale = fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(Duration.ofMinutes(31));
		Instant completedAt = fixture.clock().instant();
		fixture.attemptService().markSucceeded(stale.getId(), 200, 12L);

		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(0);
		PolicyFetchAttempt completed = attemptRepository.findById(stale.getId()).orElseThrow();
		assertThat(completed.getStatus()).isEqualTo(PolicyFetchAttemptStatus.SUCCESS);
		assertThat(completed.getFailureKind()).isNull();
		assertThat(completed.getCompletedAt()).isEqualTo(completedAt);
		// The recovery performed no reschedule: the success path never
		// writes next_check_at, so the original due time stands.
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(START.minusSeconds(60));
	}

	@Test
	void lateWorkerCompletionAfterRecoveryIsRejected() {
		Fixture fixture = fixture(5, 100);
		Policy policy = registeredPolicy(START.minusSeconds(60));
		PolicyFetchAttempt stale = fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.clock().advance(Duration.ofMinutes(31));
		Instant recoveredAt = fixture.clock().instant();
		Duration backoff = fixture.control().backoffDelay(1);
		assertThat(fixture.recovery().recoverStaleAttempts(recoveredAt)).isEqualTo(1);

		// The original worker finishing late cannot overwrite the
		// recovery: the sanctioned single terminal transition rejects it.
		UUID staleId = stale.getId();
		assertThatThrownBy(() -> fixture.attemptService().markSucceeded(staleId, 200, 12L))
				.isInstanceOf(IllegalStateException.class);
		PolicyFetchAttempt reaped = attemptRepository.findById(staleId).orElseThrow();
		assertThat(reaped.getStatus()).isEqualTo(PolicyFetchAttemptStatus.FAILED);
		assertThat(reaped.getFailureKind()).isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		assertThat(reaped.getErrorMessage()).startsWith("Stale IN_PROGRESS");
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(recoveredAt.plus(backoff));
	}

	@Test
	void manualAndScheduledCollisionsHoldBeforeRecoveryAndClearAfter() {
		Fixture fixture = fixture(5, 100);
		Policy policy = registeredPolicy(START.minusSeconds(60));
		fixture.attemptService().beginAttempt(reload(policy), PolicyFetchAttemptTrigger.MANUAL);
		assertThatThrownBy(() -> fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.MANUAL))
				.isInstanceOf(PolicyFetchClaimRejectedException.class);
		assertThatThrownBy(() -> fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED))
				.isInstanceOf(PolicyFetchClaimRejectedException.class);

		fixture.clock().advance(Duration.ofMinutes(31));
		assertThat(fixture.recovery().recoverStaleAttempts(fixture.clock().instant()))
				.isEqualTo(1);

		PolicyFetchAttempt retry = fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED);
		assertThat(retry.getAttemptNumber()).isEqualTo(2);
		assertThat(retry.getTrigger()).isEqualTo(PolicyFetchAttemptTrigger.SCHEDULED);
		fixture.attemptService().markSkippedUnchanged(retry.getId(), 200, 12L);
	}

	private Fixture fixture(int maxAttempts, int batchSize) {
		ManualClock clock = new ManualClock(START);
		// One shared retry policy per fixture: the attempt service only
		// calls retriesRemaining (no jitter draws) while each recovery
		// draws once per reschedule, so the control instance below stays
		// draw-aligned call for call.
		RetryPolicy retry = new RetryPolicy(maxAttempts, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(11));
		RetryPolicy control = new RetryPolicy(maxAttempts, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(11));
		PolicyFetchAttemptService attempts = new PolicyFetchAttemptService(
				attemptRepository, transactionManager, clock, retry);
		StaleAttemptRecovery recovery = new StaleAttemptRecovery(attemptRepository,
				policyRepository, transactionManager, clock, retry, TIMEOUT, batchSize);
		return new Fixture(clock, retry, control, attempts, recovery);
	}

	private PolicyObservationService orchestrator(Fixture fixture, PolicyFetcher fetcher) {
		return new PolicyObservationService(policyRepository, fetcher, extractor, normalizer,
				hasher, persistenceService, fixture.attemptService(), fixture.retry(),
				transactionManager);
	}

	private PolicyObservationScheduler scheduler(Fixture fixture, PolicyFetcher fetcher) {
		return new PolicyObservationScheduler(policyRepository, orchestrator(fixture, fetcher),
				transactionManager, fixture.clock(), INTERVAL);
	}

	private PolicyFetcher permanentFetcher() {
		return url -> {
			throw new PolicyFetchException("gone", 404, false);
		};
	}

	private List<PolicyFetchAttempt> attemptsOf(Policy policy) {
		return attemptRepository.findByPolicy_IdOrderByStartedAtDesc(policy.getId());
	}

	private PolicyFetchAttemptStatus statusOf(Policy policy) {
		List<PolicyFetchAttempt> history = attemptsOf(policy);
		assertThat(history).hasSize(1);
		return history.get(0).getStatus();
	}

	private Policy registeredPolicy(Instant nextCheckAt) {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
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

	private record Fixture(ManualClock clock, RetryPolicy retry, RetryPolicy control,
			PolicyFetchAttemptService attemptService, StaleAttemptRecovery recovery) {
	}

	/** Deterministic manually advanced clock. */
	private static final class ManualClock extends Clock {

		private Instant now;

		ManualClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	private static final class CountingFetcher implements PolicyFetcher {

		private final PolicyFetcher delegate;
		private final AtomicInteger invocations = new AtomicInteger();

		CountingFetcher(PolicyFetcher delegate) {
			this.delegate = delegate;
		}

		int invocations() {
			return invocations.get();
		}

		@Override
		public FetchResult fetch(String url) {
			invocations.incrementAndGet();
			return delegate.fetch(url);
		}
	}
}
