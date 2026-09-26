package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 8B — deterministic unit tests for {@link AuthLoginService}.
 *
 * <p>No Spring context, no database. A real {@link BCryptPasswordEncoder}
 * proves verification; the repositories and {@link JwtService} are
 * mocked, as is {@link AuthRefreshService}. Every failure case must
 * yield the same exception and message.
 *
 * <p>Phase 14-A/3a covers the atomic initial issuance: success
 * persists one refresh row in the login transaction, and any issuance
 * failure becomes the uniform credential failure with nothing
 * persisted.
 */
@ExtendWith(MockitoExtension.class)
class AuthLoginServiceTest {

	private static final String PASSWORD = "correct-horse-1";

	@Mock
	private UserRepository userRepository;

	@Mock
	private JwtService jwtService;

	@Mock
	private AuthRefreshService refreshService;

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	private AuthLoginService service;

	@BeforeEach
	void setUp() {
		service = new AuthLoginService(userRepository, passwordEncoder, jwtService,
				refreshService);
	}

	@Test
	void successfulLoginIssuesTokenForUserUuid() throws Exception {
		User user = credentialedUser("user@example.com", PASSWORD);
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
		when(jwtService.issueAccessToken(user.getId())).thenReturn("token-for-user");
		when(jwtService.accessTokenExpiresInSeconds()).thenReturn(900L);
		when(refreshService.issueRefreshToken(user.getId())).thenReturn(
				new RefreshResult(user.getId(), "raw-refresh-token",
						java.time.Instant.parse("2026-10-26T10:00:00Z"), 2_592_000L));

		LoginResult result = service.login("  User@Example.COM ", PASSWORD);

		assertThat(result.userId()).isEqualTo(user.getId());
		assertThat(result.accessToken()).isEqualTo("token-for-user");
		assertThat(result.expiresInSeconds()).isEqualTo(900L);
		assertThat(result.refreshToken()).isEqualTo("raw-refresh-token");
		assertThat(result.refreshExpiresInSeconds()).isEqualTo(2_592_000L);
		assertThat(result.toString()).doesNotContain(PASSWORD);
		verify(jwtService).issueAccessToken(user.getId());
		verify(refreshService).issueRefreshToken(user.getId());
	}

	@Test
	void refreshIssuanceFailureFailsWithUniformMessage() throws Exception {
		User user = credentialedUser("user@example.com", PASSWORD);
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
		when(refreshService.issueRefreshToken(user.getId()))
				.thenThrow(new RuntimeException("forced persistence failure"));

		assertThatThrownBy(() -> service.login("user@example.com", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);
		verify(jwtService, never()).issueAccessToken(any());
	}

	@Test
	void unknownEmailFailsWithUniformMessage() {
		when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.login("unknown@example.com", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);
		verify(jwtService, never()).issueAccessToken(any());
		verify(refreshService, never()).issueRefreshToken(any());
	}

	@Test
	void wrongPasswordFailsWithUniformMessage() throws Exception {
		User user = credentialedUser("user@example.com", PASSWORD);
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> service.login("user@example.com", "wrong-password-2"))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);
		verify(jwtService, never()).issueAccessToken(any());
		verify(refreshService, never()).issueRefreshToken(any());
	}

	@Test
	void nullPasswordHashFailsWithUniformMessage() throws Exception {
		User legacy = new User();
		withId(legacy, UUID.randomUUID());
		when(userRepository.findByEmail("legacy@example.com")).thenReturn(Optional.of(legacy));

		assertThatThrownBy(() -> service.login("legacy@example.com", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);
		verify(jwtService, never()).issueAccessToken(any());
	}

	@Test
	void outOfPolicyPasswordLengthsFailWithUniformMessage() {
		assertThatThrownBy(() -> service.login("user@example.com", "short-1"))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);
		assertThatThrownBy(() -> service.login("user@example.com", "a".repeat(73)))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);

		verify(userRepository, never()).findByEmail(any());
		verify(jwtService, never()).issueAccessToken(any());
		verify(refreshService, never()).issueRefreshToken(any());
	}

	private User credentialedUser(String email, String rawPassword) throws Exception {
		User user = new User();
		user.assignCredentials(email, passwordEncoder.encode(rawPassword));
		withId(user, UUID.randomUUID());
		return user;
	}

	private static void withId(User user, UUID id) throws Exception {
		Field field = User.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(user, id);
	}
}
