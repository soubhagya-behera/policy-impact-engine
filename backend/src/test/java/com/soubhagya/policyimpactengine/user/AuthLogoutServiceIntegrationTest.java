package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 15-A/2 — Testcontainers integration tests for
 * {@link AuthLogoutService} against PostgreSQL (see DECISIONS.md
 * ADR-031).
 *
 * <p>Proves single-session revocation with owner attribution, the
 * idempotent no-op contract for unknown/expired/revoked/superseded
 * digests (never a family kill, never an oracle exception), bulk
 * logout-all with zero-live no-op, user isolation, and predicate-based
 * convergence of concurrent same-token logouts with no Java locks.
 * Nothing here touches HTTP, login, filters, or audit.
 */
@SpringBootTest
@Testcontainers
class AuthLogoutServiceIntegrationTest {

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
	void liveTokenRevokesWithOwnerWhileDeadTokensAreSilentNoops() {
		AuthLogoutService logout = logoutService(fixed(T0));
		AuthRefreshService refresh = refreshService(fixed(T0), LONG_TTL);
		User user = userRepository.saveAndFlush(new User());
		RefreshResult live = refresh.issueRefreshToken(user.getId());

		LogoutResult revoked = logout.logoutSingle(live.refreshToken());

		assertThat(revoked.revoked()).isTrue();
		assertThat(revoked.userId()).isEqualTo(user.getId());

		assertThat(logout.logoutSingle(live.refreshToken())).isEqualTo(LogoutResult.noop());
		assertThat(logout.logoutSingle("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
				.isEqualTo(LogoutResult.noop());
		assertThatThrownBy(() -> logout.logoutSingle("   "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> logout.logoutSingle(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void expiredRevokedAndSupersededTokensNeverKillTheFamily() {
		AuthLogoutService logout = logoutService(fixed(T0));
		User user = userRepository.saveAndFlush(new User());
		RefreshResult first = refreshService(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		RefreshResult second = refreshService(fixed(T0), LONG_TTL).issueRefreshToken(user.getId());
		refreshService(fixed(T0), LONG_TTL).rotate(first.refreshToken());
		RefreshResult shortLived = refreshService(fixed(T0), SHORT_TTL).issueRefreshToken(user.getId());
		AuthLogoutService late = logoutService(fixed(T0.plus(Duration.ofHours(2))));

		assertThat(logout.logoutSingle(first.refreshToken())).isEqualTo(LogoutResult.noop());
		assertThat(late.logoutSingle(shortLived.refreshToken())).isEqualTo(LogoutResult.noop());

		assertThat(refreshService(fixed(T0), LONG_TTL).rotate(second.refreshToken())
				.refreshToken()).isNotEqualTo(second.refreshToken());
	}

	@Test
	void logoutAllRevokesFamilyIsolatesUsersAndNoopsWhenEmpty() {
		AuthLogoutService logout = logoutService(fixed(T0));
		AuthRefreshService refresh = refreshService(fixed(T0), LONG_TTL);
		User user = userRepository.saveAndFlush(new User());
		User other = userRepository.saveAndFlush(new User());
		refresh.issueRefreshToken(user.getId());
		refresh.issueRefreshToken(user.getId());
		RefreshResult spared = refresh.issueRefreshToken(other.getId());

		LogoutResult result = logout.logoutAll(user.getId());

		assertThat(result).isEqualTo(LogoutResult.revoked(user.getId()));
		assertThat(logout.logoutAll(user.getId())).isEqualTo(LogoutResult.noop());
		assertThat(refresh.rotate(spared.refreshToken()).refreshToken())
				.isNotEqualTo(spared.refreshToken());
		assertThatThrownBy(() -> logout.logoutAll(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(logout.logoutAll(UUID.randomUUID())).isEqualTo(LogoutResult.noop());
	}

	@Test
	void concurrentSameTokenLogoutsConvergeWithExactlyOneRevoker() throws Exception {
		AuthRefreshService refresh = refreshService(fixed(T0), LONG_TTL);
		User user = userRepository.saveAndFlush(new User());
		RefreshResult live = refresh.issueRefreshToken(user.getId());
		AuthLogoutService logout = logoutService(Clock.systemUTC());

		int threads = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<LogoutResult>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(executor.submit(() -> {
					await(start);
					return logout.logoutSingle(live.refreshToken());
				}));
			}
			start.countDown();
			AtomicInteger revokers = new AtomicInteger();
			for (Future<LogoutResult> future : futures) {
				if (future.get(60, TimeUnit.SECONDS).revoked()) {
					revokers.incrementAndGet();
				}
			}
			assertThat(revokers.get()).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private AuthLogoutService logoutService(Clock clock) {
		return new AuthLogoutService(refreshTokenRepository, transactionManager, clock);
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
