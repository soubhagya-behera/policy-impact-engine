package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 14-A/2b — Testcontainers integration tests for
 * {@link AuthRefreshService} against PostgreSQL.
 *
 * <p>Proves the V19 predicate-update serialization across real threads
 * (exactly one concurrent rotation wins, no Java locks involved), the
 * uniform failure contract, reuse-driven family revocation, and the
 * bounded purge. The service is constructed manually with fixed clocks
 * and per-scenario TTLs; nothing here touches HTTP, login, or filters.
 */
@SpringBootTest
@Testcontainers
class AuthRefreshServiceIntegrationTest {

	private static final String SECRET = "test-only-secret-for-automated-tests-not-production-000000";
	private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");
	private static final Duration LONG_TTL = Duration.ofHours(720);
	private static final Duration SHORT_TTL = Duration.ofHours(1);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void clean() {
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void issuancePersistsDigestAndRotationChainsOnce() {
		AuthRefreshService service = service(fixed(T0), LONG_TTL);
		User user = userRepository.saveAndFlush(new User());

		RefreshResult issued = service.issueRefreshToken(user.getId());

		RefreshToken row = refreshTokenRepository
				.findByTokenHash(AuthRefreshService.sha256Hex(issued.refreshToken()))
				.orElseThrow();
		assertThat(row.getUser().getId()).isEqualTo(user.getId());
		assertThat(row.getExpiresAt()).isEqualTo(T0.plus(LONG_TTL));
		assertThat(columnHashes()).doesNotContain(issued.refreshToken());

		RefreshResult second = service.rotate(issued.refreshToken());
		RefreshResult third = service.rotate(second.refreshToken());

		assertThat(second.refreshToken()).isNotEqualTo(issued.refreshToken());
		assertThat(third.refreshToken()).isNotEqualTo(second.refreshToken());
		assertThatThrownBy(() -> service.rotate(issued.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		assertThatThrownBy(() -> service.rotate(second.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
	}

	@Test
	void unknownExpiredRevokedAndReusedAllFailUniformly() {
		User user = userRepository.saveAndFlush(new User());
		RefreshResult first = service(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		RefreshResult live = service(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		service(fixed(T0), LONG_TTL).rotate(first.refreshToken());
		assertThatThrownBy(() -> service(fixed(T0), LONG_TTL).rotate(first.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		assertThat(liveRow(live).getRevokedAt()).isNotNull();
		assertThat(liveRow(live).getReplacedByTokenHash()).isNull();

		assertThatThrownBy(() -> service(fixed(T0), LONG_TTL)
				.rotate(AuthRefreshService.generateRawToken(new java.security.SecureRandom())))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		assertThatThrownBy(() -> service(fixed(T0), LONG_TTL).rotate(live.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		assertThatThrownBy(() -> service(fixed(T0), LONG_TTL).rotate(first.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		RefreshResult aged = service(fixed(T0), SHORT_TTL).issueRefreshToken(user.getId());
		assertThat(liveRow(aged).getRevokedAt()).isNull();
		assertThatThrownBy(() -> service(fixed(T0.plus(Duration.ofHours(2))), SHORT_TTL)
				.rotate(aged.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
	}

	@Test
	void reuseRevokesLiveFamilyButSparesExpiredAndForeignRows() {
		User user = userRepository.saveAndFlush(new User());
		User other = userRepository.saveAndFlush(new User());
		RefreshResult first = service(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		RefreshResult sibling = service(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		RefreshResult aged = service(fixed(T0), SHORT_TTL).issueRefreshToken(user.getId());
		RefreshResult foreign = service(fixed(T0), LONG_TTL).issueRefreshToken(other.getId());
		service(fixed(T0), LONG_TTL).rotate(first.refreshToken());

		assertThatThrownBy(() -> service(fixed(T0.plus(Duration.ofMinutes(30))), LONG_TTL)
				.rotate(first.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		assertThat(liveRow(sibling).getRevokedAt())
				.isEqualTo(T0.plus(Duration.ofMinutes(30)));
		assertThat(liveRow(aged).getRevokedAt()).isNotNull();
		assertThat(liveRow(foreign).getRevokedAt()).isNull();
		assertThatThrownBy(() -> service(fixed(T0), LONG_TTL).rotate(sibling.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
	}

	@Test
	void expiredSupersededPresentationTriggersNoFamilyRevocation() {
		User user = userRepository.saveAndFlush(new User());
		RefreshResult first = service(fixed(T0), SHORT_TTL).issueRefreshToken(user.getId());
		RefreshResult sibling = service(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		service(fixed(T0.plus(Duration.ofMinutes(30))), SHORT_TTL)
				.rotate(first.refreshToken());

		assertThatThrownBy(() -> service(fixed(T0.plus(Duration.ofHours(3))), SHORT_TTL)
				.rotate(first.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		assertThat(liveRow(sibling).getRevokedAt()).isNull();
	}

	@Test
	void concurrentSameTokenRotationHasExactlyOneWinner() throws Exception {
		User user = userRepository.saveAndFlush(new User());
		RefreshResult issued = service(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		AuthRefreshService racer = service(fixed(T0), LONG_TTL);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<RefreshResult> winner = new AtomicReference<>();
		AtomicReference<Throwable> loser = new AtomicReference<>();
		try {
			Future<?> f1 = executor.submit(() -> {
				await(start);
				try {
					winner.compareAndSet(null, racer.rotate(issued.refreshToken()));
				}
				catch (Throwable failed) {
					loser.compareAndSet(null, failed);
				}
			});
			Future<?> f2 = executor.submit(() -> {
				await(start);
				try {
					winner.compareAndSet(null, racer.rotate(issued.refreshToken()));
				}
				catch (Throwable failed) {
					loser.compareAndSet(null, failed);
				}
			});
			start.countDown();
			f1.get(60, TimeUnit.SECONDS);
			f2.get(60, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(winner.get()).isNotNull();
		assertThat(loser.get()).isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		assertThat(refreshTokenRepository.count()).isEqualTo(2);
		RefreshToken successor = refreshTokenRepository.findByTokenHash(
				AuthRefreshService.sha256Hex(winner.get().refreshToken())).orElseThrow();
		assertThat(successor.getUser().getId()).isEqualTo(user.getId());
	}

	@Test
	void scheduledPurgeRemovesOnlyExpiredRows() {
		User user = userRepository.saveAndFlush(new User());
		service(fixed(T0), SHORT_TTL).issueRefreshToken(user.getId());
		RefreshResult live = service(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		AuthRefreshService sweeper = service(fixed(T0.plus(Duration.ofHours(2))), SHORT_TTL);

		sweeper.purgeExpiredTokensScheduled();

		assertThat(refreshTokenRepository.count()).isEqualTo(1);
		assertThat(liveRow(live).getRevokedAt()).isNull();
	}

	@Test
	void expiryUsesInjectedClockAndConfiguredTtl() {
		User user = userRepository.saveAndFlush(new User());

		RefreshResult result = service(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());

		assertThat(result.expiresAt()).isEqualTo(T0.plus(LONG_TTL));
		assertThat(result.expiresInSeconds()).isEqualTo(LONG_TTL.toSeconds());
	}

	private AuthRefreshService service(Clock clock, Duration refreshTtl) {
		JwtService jwtService = new JwtService(
				new JwtProperties(SECRET, Duration.ofMinutes(15), refreshTtl), clock);
		return new AuthRefreshService(refreshTokenRepository, userRepository, jwtService,
				transactionManager, clock);
	}

	private static Clock fixed(Instant now) {
		return Clock.fixed(now, ZoneOffset.UTC);
	}

	private RefreshToken liveRow(RefreshResult result) {
		return refreshTokenRepository
				.findByTokenHash(AuthRefreshService.sha256Hex(result.refreshToken()))
				.orElseThrow();
	}

	private java.util.List<String> columnHashes() {
		return refreshTokenRepository.findAll().stream()
				.map(RefreshToken::getTokenHash).toList();
	}

	private static void await(CountDownLatch start) {
		try {
			start.await();
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("race start interrupted", interrupted);
		}
	}
}
