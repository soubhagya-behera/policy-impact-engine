package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 15-B/2 — Testcontainers integration tests for the reuse branch
 * of {@link AuthRefreshService#rotate} (see DECISIONS.md ADR-032).
 *
 * <p>Proves a superseded presentation with a live family raises
 * {@link RefreshReuseDetectedException} carrying only the owning user
 * id and the revoked-live count (same message, no token material),
 * while repeats, zero-live kills, expired superseded rows, revoked
 * rows without a successor, unknown digests, and predicate losers
 * raise the exact normal invalid signal. Concurrent same-digest
 * presentations converge with exactly one reuse attribution and no
 * Java locks. Nothing here touches HTTP, login, filters, or audit.
 */
@SpringBootTest
@Testcontainers
class AuthRefreshReuseServiceIntegrationTest {

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
	void supersededWithLiveFamilyRaisesReuseWithOwnerAndCountOnly() {
		AuthRefreshService refresh = refreshService(fixed(T0), LONG_TTL);
		User user = userRepository.saveAndFlush(new User());
		RefreshResult first = refresh.issueRefreshToken(user.getId());
		refresh.issueRefreshToken(user.getId());
		refresh.rotate(first.refreshToken());

		RefreshReuseDetectedException reuse =
				catchReuse(() -> refresh.rotate(first.refreshToken()));

		assertThat(reuse.getUserId()).isEqualTo(user.getId());
		assertThat(reuse.getRevokedLiveCount()).isEqualTo(2);
		assertThat(reuse.getMessage())
				.isEqualTo(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		assertThat(reuse.getMessage()).doesNotContain(first.refreshToken());
		assertThat(allRevoked(user)).isTrue();
	}

	@Test
	void repeatAndZeroLiveKillsRaiseExactNormalInvalid() {
		AuthRefreshService refresh = refreshService(fixed(T0), LONG_TTL);
		User user = userRepository.saveAndFlush(new User());
		RefreshResult first = refresh.issueRefreshToken(user.getId());
		refresh.issueRefreshToken(user.getId());
		refresh.rotate(first.refreshToken());
		catchReuse(() -> refresh.rotate(first.refreshToken()));

		assertThatThrownBy(() -> refresh.rotate(first.refreshToken()))
				.satisfies(thrown -> assertThat(thrown.getClass())
						.isEqualTo(InvalidRefreshTokenException.class))
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		AuthRefreshService late = refreshService(fixed(T0.plus(Duration.ofHours(2))), LONG_TTL);
		RefreshResult lone = late.issueRefreshToken(user.getId());
		late.rotate(lone.refreshToken());
		catchReuse(() -> late.rotate(lone.refreshToken()));
		assertThatThrownBy(() -> late.rotate(lone.refreshToken()))
				.satisfies(thrown -> assertThat(thrown.getClass())
						.isEqualTo(InvalidRefreshTokenException.class))
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
	}

	@Test
	void expiredSupersededRevokedWithoutSuccessorAndUnknownStaySilent() {
		AuthRefreshService refresh = refreshService(fixed(T0), LONG_TTL);
		User user = userRepository.saveAndFlush(new User());
		RefreshResult aged = refreshService(fixed(T0), SHORT_TTL).issueRefreshToken(user.getId());
		RefreshResult sibling = refresh.issueRefreshToken(user.getId());
		refreshService(fixed(T0.plus(Duration.ofMinutes(30))), SHORT_TTL)
				.rotate(aged.refreshToken());

		AuthRefreshService late = refreshService(fixed(T0.plus(Duration.ofHours(3))), SHORT_TTL);
		assertThatThrownBy(() -> late.rotate(aged.refreshToken()))
				.satisfies(thrown -> assertThat(thrown.getClass())
						.isEqualTo(InvalidRefreshTokenException.class))
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		assertThat(liveRow(sibling).getRevokedAt()).isNull();

		RefreshResult ended = refresh.issueRefreshToken(user.getId());
		refreshTokenRepository.revokeLiveTokensForUser(user.getId(),
				T0.plus(Duration.ofMinutes(31)), T0.plus(Duration.ofMinutes(31)));
		assertThatThrownBy(() -> refresh.rotate(ended.refreshToken()))
				.satisfies(thrown -> assertThat(thrown.getClass())
						.isEqualTo(InvalidRefreshTokenException.class))
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		assertThatThrownBy(() -> refresh.rotate(
				AuthRefreshService.generateRawToken(new java.security.SecureRandom())))
				.satisfies(thrown -> assertThat(thrown.getClass())
						.isEqualTo(InvalidRefreshTokenException.class))
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
	}

	@Test
	void concurrentSameSupersededTokenConvergesToOneReuseAttribution() throws Exception {
		AuthRefreshService refresh = refreshService(fixed(T0), LONG_TTL);
		User user = userRepository.saveAndFlush(new User());
		RefreshResult first = refresh.issueRefreshToken(user.getId());
		refresh.issueRefreshToken(user.getId());
		refresh.rotate(first.refreshToken());
		AuthRefreshService racer = refreshService(fixed(T0), LONG_TTL);

		int threads = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Throwable>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(executor.submit(() -> {
					await(start);
					try {
						racer.rotate(first.refreshToken());
						return null;
					}
					catch (Throwable failed) {
						return failed;
					}
				}));
			}
			start.countDown();
			AtomicInteger reuseCount = new AtomicInteger();
			for (Future<Throwable> future : futures) {
				Throwable thrown = future.get(60, TimeUnit.SECONDS);
				assertThat(thrown).isInstanceOf(InvalidRefreshTokenException.class)
						.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
				if (thrown instanceof RefreshReuseDetectedException reuse) {
					assertThat(reuse.getUserId()).isEqualTo(user.getId());
					assertThat(reuse.getRevokedLiveCount()).isPositive();
					reuseCount.incrementAndGet();
				}
			}
			assertThat(reuseCount.get()).isEqualTo(1);
			assertThat(allRevoked(user)).isTrue();
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static RefreshReuseDetectedException catchReuse(
			org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		try {
			call.call();
		}
		catch (RefreshReuseDetectedException reuse) {
			return reuse;
		}
		catch (Throwable unexpected) {
			throw new AssertionError("Expected RefreshReuseDetectedException but got "
					+ unexpected, unexpected);
		}
		throw new AssertionError("Expected RefreshReuseDetectedException but nothing was thrown");
	}

	private boolean allRevoked(User user) {
		return refreshTokenRepository
				.findByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(user.getId(),
						T0.plus(Duration.ofHours(3)))
				.isEmpty();
	}

	private RefreshToken liveRow(RefreshResult result) {
		return refreshTokenRepository
				.findByTokenHash(AuthRefreshService.sha256Hex(result.refreshToken()))
				.orElseThrow();
	}

	private AuthRefreshService refreshService(Clock clock, Duration refreshTtl) {
		JwtService jwtService = new JwtService(
				new JwtProperties(SECRET, Duration.ofMinutes(15), refreshTtl), clock);
		return new AuthRefreshService(refreshTokenRepository, userRepository, jwtService,
				transactionManager, clock);
	}

	private static Clock fixed(Instant now) {
		return Clock.fixed(now, ZoneOffset.UTC);
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
