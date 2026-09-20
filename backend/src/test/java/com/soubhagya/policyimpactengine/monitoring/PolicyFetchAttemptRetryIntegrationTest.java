package com.soubhagya.policyimpactengine.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;

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

import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchClaimRejectedException;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
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
 * Phase 2U.1 — Testcontainers retry-chain tests over real PostgreSQL.
 *
 * <p>Proves the deferred-retry design end to end: every retry is a new
 * terminal row with an incremented number, scheduled through
 * {@code next_check_at} (nothing is ever held {@code PENDING} across a
 * backoff window), exhaustion and permanent failures resume the regular
 * interval and reset the chain, and the V11 claim invariant still guards
 * every retry claim. A frozen clock plus a seeded retry policy make every
 * timestamp and backoff exact; the clock advances one second between
 * observations so newest-first history ordering stays deterministic.
 */
@SpringBootTest
@Testcontainers
class PolicyFetchAttemptRetryIntegrationTest {

	private static final Instant START = Instant.parse("2026-09-18T10:00:00Z");
	private static final Duration INTERVAL = Duration.ofHours(24);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
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
		policyRepository.deleteAll();
	}

	@Test
	void transientChainIncrementsNumbersWithExactBackoff() {
		Fixture fixture = fixture(5);
		Policy policy = registeredPolicy();
		Duration first = fixture.control().backoffDelay(1);
		Duration second = fixture.control().backoffDelay(2);
		Duration third = fixture.control().backoffDelay(3);

		failTransient(fixture, policy);
		Instant failedAt1 = fixture.clock().instant();
		fixture.clock().advance(Duration.ofSeconds(1));
		failTransient(fixture, policy);
		Instant failedAt2 = fixture.clock().instant();
		fixture.clock().advance(Duration.ofSeconds(1));
		failTransient(fixture, policy);
		Instant failedAt3 = fixture.clock().instant();

		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(3);
		assertThat(attempts)
				.extracting(PolicyFetchAttempt::getAttemptNumber)
				.containsExactly(3, 2, 1);
		assertThat(attempts)
				.extracting(PolicyFetchAttempt::getFailureKind)
				.containsExactly(PolicyFetchFailureKind.TRANSIENT,
						PolicyFetchFailureKind.TRANSIENT, PolicyFetchFailureKind.TRANSIENT);
		assertThat(attempts).allMatch(attempt -> attempt.getStatus() == PolicyFetchAttemptStatus.FAILED);
		assertThat(attempts.get(2).getCompletedAt()).isEqualTo(failedAt1);
		assertThat(attempts.get(1).getCompletedAt()).isEqualTo(failedAt2);
		assertThat(attempts.get(0).getCompletedAt()).isEqualTo(failedAt3);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(failedAt3.plus(third));
		assertThat(first).isLessThan(second);
		assertThat(second).isLessThan(third);
	}

	@Test
	void exhaustedChainResumesIntervalAndResets() {
		Fixture fixture = fixture(3);
		Policy policy = registeredPolicy();
		Duration first = fixture.control().backoffDelay(1);
		Duration second = fixture.control().backoffDelay(2);
		Duration restarted = fixture.control().backoffDelay(1);

		failTransient(fixture, policy);
		fixture.clock().advance(Duration.ofSeconds(1));
		failTransient(fixture, policy);
		fixture.clock().advance(Duration.ofSeconds(1));
		failTransient(fixture, policy);
		Instant exhaustedAt = fixture.clock().instant();
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(exhaustedAt.plus(INTERVAL));

		fixture.clock().advance(Duration.ofSeconds(1));
		failTransient(fixture, policy);
		Instant restartedAt = fixture.clock().instant();

		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts)
				.extracting(PolicyFetchAttempt::getAttemptNumber)
				.containsExactly(1, 3, 2, 1);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(restartedAt.plus(restarted));
		assertThat(first).isLessThan(second);
	}

	@Test
	void permanentFailureSchedulesIntervalAndResetsChain() {
		Fixture fixture = fixture(5);
		Policy policy = registeredPolicy();
		// Mirror the service-side jitter draws in order: the first
		// transient failure consumes one draw for its own (overwritten)
		// backoff, the restarted failure consumes the next.
		fixture.control().backoffDelay(1);
		Duration restarted = fixture.control().backoffDelay(1);

		failTransient(fixture, policy);
		fixture.clock().advance(Duration.ofSeconds(1));
		assertThatThrownBy(() -> orchestrator(fixture, permanentFetcher()).observe(policy.getId()))
				.isInstanceOf(PolicyFetchException.class);
		Instant permanentAt = fixture.clock().instant();
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(permanentAt.plus(INTERVAL));

		fixture.clock().advance(Duration.ofSeconds(1));
		failTransient(fixture, policy);
		Instant restartedAt = fixture.clock().instant();

		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts)
				.extracting(PolicyFetchAttempt::getAttemptNumber)
				.containsExactly(1, 2, 1);
		assertThat(attempts)
				.extracting(PolicyFetchAttempt::getFailureKind)
				.containsExactly(PolicyFetchFailureKind.TRANSIENT,
						PolicyFetchFailureKind.PERMANENT, PolicyFetchFailureKind.TRANSIENT);
		assertThat(attempts.get(1).getHttpStatus()).isEqualTo(404);
		assertThat(attempts.get(1).getCompletedAt()).isEqualTo(permanentAt);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(restartedAt.plus(restarted));
	}

	@Test
	void nullLegacyFailureKindResetsChain() {
		Fixture fixture = fixture(5);
		Policy policy = registeredPolicy();
		jdbcTemplate.update(
				"INSERT INTO policy_fetch_attempt (id, policy_id, trigger, status, attempt_number, "
						+ "started_at, completed_at, failure_kind) VALUES (?, ?, 'MANUAL', 'FAILED', 7, "
						+ "TIMESTAMP '2026-09-18 09:00:00+00', TIMESTAMP '2026-09-18 09:00:01+00', NULL)",
				UUID.randomUUID(), policy.getId());
		Duration first = fixture.control().backoffDelay(1);
		fixture.clock().advance(Duration.ofSeconds(1));

		failTransient(fixture, policy);
		Instant failedAt = fixture.clock().instant();

		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(2);
		assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
		assertThat(attempts.get(0).getFailureKind()).isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		assertThat(reload(policy).getNextCheckAt()).isEqualTo(failedAt.plus(first));
	}

	@Test
	void manualTransientFailureIsASingleAttemptWithBackoff() {
		Fixture fixture = fixture(5);
		Policy policy = registeredPolicy();
		Duration first = fixture.control().backoffDelay(1);
		CountingFetcher fetcher = new CountingFetcher(transientFetcher());

		assertThatThrownBy(() -> orchestrator(fixture, fetcher).observe(policy.getId()))
				.isInstanceOf(PolicyFetchException.class);

		assertThat(fetcher.invocations()).isEqualTo(1);
		List<PolicyFetchAttempt> attempts = attemptsOf(policy);
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
		assertThat(attempts.get(0).getFailureKind()).isEqualTo(PolicyFetchFailureKind.TRANSIENT);
		assertThat(reload(policy).getNextCheckAt())
				.isEqualTo(attempts.get(0).getCompletedAt().plus(first));
	}

	@Test
	void v11GuardsRetryClaimsWithoutPinningSlots() {
		Fixture fixture = fixture(5);
		Policy policy = registeredPolicy();

		failTransient(fixture, policy);

		// Between retries no non-terminal row exists: the backoff window
		// holds no V11 slot, so other work is never blocked.
		assertThat(attemptRepository.findAll())
				.allMatch(attempt -> attempt.getStatus() == PolicyFetchAttemptStatus.FAILED);

		// The retry claim proceeds (chain continues at 2) while a
		// simultaneous second claim for the same policy is still rejected
		// by the V11 index.
		PolicyFetchAttempt retry = fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.MANUAL);
		assertThat(retry.getAttemptNumber()).isEqualTo(2);
		assertThatThrownBy(() -> fixture.attemptService().beginAttempt(
				reload(policy), PolicyFetchAttemptTrigger.SCHEDULED))
				.isInstanceOf(PolicyFetchClaimRejectedException.class);
		fixture.attemptService().markFailed(retry.getId(), null, null, "cleanup",
				PolicyFetchFailureKind.TRANSIENT);
	}

	private void failTransient(Fixture fixture, Policy policy) {
		assertThatThrownBy(() -> orchestrator(fixture, transientFetcher()).observe(policy.getId()))
				.isInstanceOf(PolicyFetchException.class);
	}

	private PolicyFetcher transientFetcher() {
		return url -> {
			throw new PolicyFetchException("connection reset", null, new IOException("reset"), true);
		};
	}

	private PolicyFetcher permanentFetcher() {
		return url -> {
			throw new PolicyFetchException("gone", 404, false);
		};
	}

	private Fixture fixture(int maxAttempts) {
		ManualClock clock = new ManualClock(START);
		// One shared policy, mirroring the production singleton: the
		// attempt service only calls retriesRemaining (no jitter draws)
		// while the observation path draws once per scheduled backoff, so
		// the control instance below stays draw-aligned call for call.
		RetryPolicy retry = new RetryPolicy(maxAttempts, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(11));
		RetryPolicy control = new RetryPolicy(maxAttempts, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random(11));
		PolicyFetchAttemptService attempts = new PolicyFetchAttemptService(
				attemptRepository, transactionManager, clock, retry);
		return new Fixture(clock, retry, control, attempts);
	}

	private PolicyObservationService orchestrator(Fixture fixture, PolicyFetcher fetcher) {
		return new PolicyObservationService(policyRepository, fetcher, extractor, normalizer,
				hasher, persistenceService, fixture.attemptService(), fixture.retry(),
				transactionManager);
	}

	private List<PolicyFetchAttempt> attemptsOf(Policy policy) {
		return attemptRepository.findByPolicy_IdOrderByStartedAtDesc(policy.getId());
	}

	private Policy registeredPolicy() {
		return policyRepository.saveAndFlush(
				new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
	}

	private Policy reload(Policy policy) {
		return policyRepository.findById(policy.getId()).orElseThrow();
	}

	private record Fixture(ManualClock clock, RetryPolicy retry, RetryPolicy control,
			PolicyFetchAttemptService attemptService) {
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
		private int invocations;

		CountingFetcher(PolicyFetcher delegate) {
			this.delegate = delegate;
		}

		int invocations() {
			return invocations;
		}

		@Override
		public FetchResult fetch(String url) {
			invocations++;
			return delegate.fetch(url);
		}
	}
}
