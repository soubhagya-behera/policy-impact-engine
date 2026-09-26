package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 14-A/2b — deterministic unit tests for {@link AuthRefreshService}.
 *
 * <p>No Spring context, no database. A manually fixed clock makes expiry
 * exact; the transaction manager is a Mockito mock following the
 * established {@code TransactionTemplate} unit-test pattern. Every
 * rotation failure must yield the same exception and message.
 */
@ExtendWith(MockitoExtension.class)
class AuthRefreshServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
	private static final long TTL_SECONDS = 2_592_000L;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private JwtService jwtService;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private ManualClock clock;
	private AuthRefreshService service;

	@BeforeEach
	void setUp() {
		clock = new ManualClock(NOW);
		service = new AuthRefreshService(refreshTokenRepository, userRepository, jwtService,
				transactionManager, clock, new SecureRandom());
	}

	private void givenTtl() {
		when(jwtService.refreshTokenExpiresInSeconds()).thenReturn(TTL_SECONDS);
	}

	private void givenTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	@Test
	void generatedTokenCarries256BitsOfBase64UrlEntropy() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			String raw = AuthRefreshService.generateRawToken(new SecureRandom());
			assertThat(raw).hasSize(43).matches("[A-Za-z0-9_-]*");
			assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
			assertThat(seen.add(raw)).as("duplicate token generated").isTrue();
		}
	}

	@Test
	void storedDigestIsLowercaseSha256Hex() throws Exception {
		String raw = AuthRefreshService.generateRawToken(new SecureRandom());
		String expected = HexFormatHolder.format(raw);

		String digest = AuthRefreshService.sha256Hex(raw);

		assertThat(digest).isEqualTo(expected).hasSize(64).matches("[0-9a-f]*");
	}

	@Test
	void issuancePersistsDigestNeverRaw() {
		givenTransaction();
		givenTtl();
		User user = userWithId(UUID.randomUUID());
		when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
		ArgumentCaptor<RefreshToken> stored = ArgumentCaptor.forClass(RefreshToken.class);
		when(refreshTokenRepository.saveAndFlush(stored.capture()))
				.thenAnswer(call -> call.getArgument(0));

		RefreshResult result = service.issueRefreshToken(user.getId());

		assertThat(result.userId()).isEqualTo(user.getId());
		assertThat(result.refreshToken()).hasSize(43);
		assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(TTL_SECONDS));
		assertThat(result.expiresInSeconds()).isEqualTo(TTL_SECONDS);
		assertThat(stored.getValue().getTokenHash())
				.isEqualTo(AuthRefreshService.sha256Hex(result.refreshToken()));
		assertThat(stored.getValue().getTokenHash()).doesNotContain(result.refreshToken());
		assertThat(stored.getValue().getCreatedAt()).isEqualTo(NOW);
		assertThat(stored.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(TTL_SECONDS));
	}

	@Test
	void issuanceRejectsUnknownUserWithoutPersisting() {
		UUID unknown = UUID.randomUUID();
		when(userRepository.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.issueRefreshToken(unknown))
				.isInstanceOf(IllegalArgumentException.class);
		verify(refreshTokenRepository, never()).saveAndFlush(any());
	}

	@Test
	void rotationConsumesAndInsertsSuccessorAtomically() {
		givenTransaction();
		givenTtl();
		User user = userWithId(UUID.randomUUID());
		String presented = AuthRefreshService.generateRawToken(new SecureRandom());
		RefreshToken live = new RefreshToken(user, AuthRefreshService.sha256Hex(presented),
				NOW.minusSeconds(60), NOW.plusSeconds(TTL_SECONDS));
		when(refreshTokenRepository.findByTokenHash(live.getTokenHash()))
				.thenReturn(Optional.of(live));
		when(refreshTokenRepository.consumeToken(any(), any(), any(), any())).thenReturn(1);
		ArgumentCaptor<RefreshToken> successor = ArgumentCaptor.forClass(RefreshToken.class);
		when(refreshTokenRepository.saveAndFlush(successor.capture()))
				.thenAnswer(call -> call.getArgument(0));

		RefreshResult result = service.rotate(presented);

		assertThat(result.userId()).isEqualTo(user.getId());
		assertThat(result.refreshToken()).isNotEqualTo(presented);
		assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(TTL_SECONDS));
		verify(refreshTokenRepository).consumeToken(live.getTokenHash(),
				AuthRefreshService.sha256Hex(result.refreshToken()), NOW, NOW);
		assertThat(successor.getValue().getUser()).isSameAs(user);
		assertThat(successor.getValue().getTokenHash())
				.isEqualTo(AuthRefreshService.sha256Hex(result.refreshToken()));
		assertThat(successor.getValue().getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void unknownBlankExpiredAndRevokedAllFailUniformly() {
		givenTransaction();
		givenTtl();
		User user = userWithId(UUID.randomUUID());
		when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.rotate(AuthRefreshService
				.generateRawToken(new SecureRandom())))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		assertThatThrownBy(() -> service.rotate(null))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);
		assertThatThrownBy(() -> service.rotate("   "))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		String expiredRaw = AuthRefreshService.generateRawToken(new SecureRandom());
		RefreshToken expired = new RefreshToken(user,
				AuthRefreshService.sha256Hex(expiredRaw), NOW.minusSeconds(7200),
				NOW.minusSeconds(3600));
		when(refreshTokenRepository.findByTokenHash(expired.getTokenHash()))
				.thenReturn(Optional.of(expired));
		assertThatThrownBy(() -> service.rotate(expiredRaw))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		String revokedRaw = AuthRefreshService.generateRawToken(new SecureRandom());
		RefreshToken revoked = new RefreshToken(user,
				AuthRefreshService.sha256Hex(revokedRaw), NOW.minusSeconds(60),
				NOW.plusSeconds(TTL_SECONDS));
		revoked.revoke(NOW);
		when(refreshTokenRepository.findByTokenHash(revoked.getTokenHash()))
				.thenReturn(Optional.of(revoked));
		assertThatThrownBy(() -> service.rotate(revokedRaw))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		verify(refreshTokenRepository, never()).revokeLiveTokensForUser(any(), any(), any());
		verify(refreshTokenRepository, never()).consumeToken(any(), any(), any(), any());
		verify(refreshTokenRepository, never()).saveAndFlush(any());
	}

	@Test
	void reuseRevokesFamilyThenFailsUniformly() {
		givenTransaction();
		givenTtl();
		User user = userWithId(UUID.randomUUID());
		String reusedRaw = AuthRefreshService.generateRawToken(new SecureRandom());
		RefreshToken superseded = new RefreshToken(user,
				AuthRefreshService.sha256Hex(reusedRaw), NOW.minusSeconds(120),
				NOW.plusSeconds(TTL_SECONDS));
		superseded.consume(NOW.minusSeconds(60), "a".repeat(64));
		when(refreshTokenRepository.findByTokenHash(superseded.getTokenHash()))
				.thenReturn(Optional.of(superseded));

		assertThatThrownBy(() -> service.rotate(reusedRaw))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		verify(refreshTokenRepository).revokeLiveTokensForUser(user.getId(), NOW, NOW);
		verify(refreshTokenRepository, never()).consumeToken(any(), any(), any(), any());
		verify(refreshTokenRepository, never()).saveAndFlush(any());
	}

	@Test
	void concurrentLoserFailsUniformlyWithoutSuccessor() {
		givenTransaction();
		givenTtl();
		User user = userWithId(UUID.randomUUID());
		String presented = AuthRefreshService.generateRawToken(new SecureRandom());
		RefreshToken live = new RefreshToken(user, AuthRefreshService.sha256Hex(presented),
				NOW.minusSeconds(60), NOW.plusSeconds(TTL_SECONDS));
		when(refreshTokenRepository.findByTokenHash(live.getTokenHash()))
				.thenReturn(Optional.of(live));
		when(refreshTokenRepository.consumeToken(any(), any(), any(), any())).thenReturn(0);

		assertThatThrownBy(() -> service.rotate(presented))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage(AuthRefreshService.INVALID_REFRESH_TOKEN_MESSAGE);

		verify(refreshTokenRepository, never()).saveAndFlush(any());
		verify(refreshTokenRepository, never()).revokeLiveTokensForUser(any(), any(), any());
	}

	@Test
	void expiryFollowsInjectedClock() {
		givenTransaction();
		givenTtl();
		User user = userWithId(UUID.randomUUID());
		when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
		when(refreshTokenRepository.saveAndFlush(any(RefreshToken.class)))
				.thenAnswer(call -> call.getArgument(0));

		clock.advance(Duration.ofHours(3));
		RefreshResult result = service.issueRefreshToken(user.getId());

		assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(3)).plusSeconds(TTL_SECONDS));
	}

	@Test
	void purgeDelegatesToRepositoryWithClockTime() {
		when(refreshTokenRepository.purgeExpired(NOW)).thenReturn(4);

		assertThat(service.purgeExpiredTokens(NOW)).isEqualTo(4);
		assertThatThrownBy(() -> service.purgeExpiredTokens(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void serviceUsesNoJavaLocks() {
		for (var method : AuthRefreshService.class.getDeclaredMethods()) {
			assertThat(Modifier.isSynchronized(method.getModifiers()))
					.as("method %s", method.getName()).isFalse();
		}
		for (Field field : AuthRefreshService.class.getDeclaredFields()) {
			assertThat(java.util.concurrent.locks.Lock.class.isAssignableFrom(field.getType()))
					.as("field %s", field.getName()).isFalse();
		}
	}

	private static User userWithId(UUID id) {
		User user = new User();
		try {
			Field field = User.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(user, id);
		}
		catch (ReflectiveOperationException failed) {
			throw new IllegalStateException("Cannot assign user id", failed);
		}
		return user;
	}

	/** Deterministic manually fixed clock for exact expiry assertions. */
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

	/** Direct JDK SHA-256 oracle, independent of the service helper. */
	private static final class HexFormatHolder {

		static String format(String raw) throws Exception {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(raw.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(64);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
	}
}
