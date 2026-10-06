package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 18-B — unit tests for {@link AuthGoogleService} (see DECISIONS.md
 * ADR-037).
 *
 * <p>No Spring context, no database. The repository, {@link JwtService},
 * and {@link AuthRefreshService} are mocked. Every case proves the
 * identity policy: resolve by stable subject, create Google-only rows
 * for verified new emails, never merge on email alone, and never
 * return success without a usable refresh token.
 */
@ExtendWith(MockitoExtension.class)
class AuthGoogleServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private JwtService jwtService;

	@Mock
	private AuthRefreshService refreshService;

	private AuthGoogleService service;

	@BeforeEach
	void setUp() {
		service = new AuthGoogleService(userRepository, jwtService, refreshService);
	}

	@Test
	void firstVerifiedLoginCreatesGoogleOnlyUserAndIssuesTokens() throws Exception {
		UUID userId = UUID.randomUUID();
		when(userRepository.findByGoogleSub("google-sub-1")).thenReturn(Optional.empty());
		when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User saved = invocation.getArgument(0);
			withId(saved, userId);
			return saved;
		});
		when(jwtService.issueAccessToken(userId)).thenReturn("access-for-user");
		when(jwtService.accessTokenExpiresInSeconds()).thenReturn(900L);
		when(refreshService.issueRefreshToken(userId)).thenReturn(
				new RefreshResult(userId, "raw-refresh-token",
						Instant.parse("2026-10-26T10:00:00Z"), 2_592_000L));

		LoginResult result = service.login(new GoogleIdentity("google-sub-1",
				"  New@Example.COM ", true));

		assertThat(result.userId()).isEqualTo(userId);
		assertThat(result.accessToken()).isEqualTo("access-for-user");
		assertThat(result.refreshToken()).isEqualTo("raw-refresh-token");
	}

	@Test
	void createdUserCarriesNormalizedEmailNullPasswordAndSubject() throws Exception {
		when(userRepository.findByGoogleSub("google-sub-2")).thenReturn(Optional.empty());
		when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User saved = invocation.getArgument(0);
			withId(saved, UUID.randomUUID());
			return saved;
		});

		UUID resolved = service.resolveLocalUser(new GoogleIdentity("google-sub-2",
				"  New@Example.COM ", true));

		assertThat(resolved).isNotNull();
		org.mockito.ArgumentCaptor<User> created = org.mockito.ArgumentCaptor
				.forClass(User.class);
		verify(userRepository).saveAndFlush(created.capture());
		assertThat(created.getValue().getEmail()).isEqualTo("new@example.com");
		assertThat(created.getValue().getPasswordHash()).isNull();
		assertThat(created.getValue().getGoogleSub()).isEqualTo("google-sub-2");
	}

	@Test
	void unverifiedEmailIsRejectedWithoutWrites() {
		assertThatThrownBy(() -> service.resolveLocalUser(new GoogleIdentity(
				"google-sub-3", "unverified@example.com", false)))
				.isInstanceOf(InvalidGoogleIdentityException.class)
				.hasMessage(AuthGoogleService.INVALID_IDENTITY_MESSAGE);

		verify(userRepository, never()).saveAndFlush(any());
		verify(refreshService, never()).issueRefreshToken(any());
		verify(jwtService, never()).issueAccessToken(any());
	}

	@Test
	void nullIdentityIsRejectedWithoutWrites() {
		assertThatThrownBy(() -> service.resolveLocalUser(null))
				.isInstanceOf(InvalidGoogleIdentityException.class)
				.hasMessage(AuthGoogleService.INVALID_IDENTITY_MESSAGE);

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void existingSubjectReturnsSameUserWithoutNewRow() throws Exception {
		User existing = googleUser("known@example.com", "google-sub-4");
		when(userRepository.findByGoogleSub("google-sub-4"))
				.thenReturn(Optional.of(existing));

		UUID resolved = service.resolveLocalUser(new GoogleIdentity("google-sub-4",
				"known@example.com", true));

		assertThat(resolved).isEqualTo(existing.getId());
		verify(userRepository, never()).saveAndFlush(any());
		verify(userRepository, never()).updateEmail(any(), any());
	}

	@Test
	void existingSubjectWithChangedVerifiedEmailUpdatesWhenFree() throws Exception {
		User existing = googleUser("old@example.com", "google-sub-5");
		when(userRepository.findByGoogleSub("google-sub-5"))
				.thenReturn(Optional.of(existing));
		when(userRepository.findByEmail("changed@example.com")).thenReturn(Optional.empty());
		when(userRepository.updateEmail(existing.getId(), "changed@example.com"))
				.thenReturn(1);

		UUID resolved = service.resolveLocalUser(new GoogleIdentity("google-sub-5",
				"changed@example.com", true));

		assertThat(resolved).isEqualTo(existing.getId());
		verify(userRepository).updateEmail(existing.getId(), "changed@example.com");
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void existingSubjectWithCollidingEmailFailsWithoutOverwrite() throws Exception {
		User existing = googleUser("old@example.com", "google-sub-6");
		User other = credentialedUser("taken@example.com");
		when(userRepository.findByGoogleSub("google-sub-6"))
				.thenReturn(Optional.of(existing));
		when(userRepository.findByEmail("taken@example.com"))
				.thenReturn(Optional.of(other));

		assertThatThrownBy(() -> service.resolveLocalUser(new GoogleIdentity(
				"google-sub-6", "taken@example.com", true)))
				.isInstanceOf(InvalidGoogleIdentityException.class)
				.hasMessage(AuthGoogleService.INVALID_IDENTITY_MESSAGE);

		verify(userRepository, never()).updateEmail(any(), any());
		verify(userRepository, never()).saveAndFlush(any());
		verify(refreshService, never()).issueRefreshToken(any());
	}

	@Test
	void emailCollisionNeverMergesAndWritesNothing() throws Exception {
		User local = credentialedUser("local@example.com");
		when(userRepository.findByGoogleSub("google-sub-7")).thenReturn(Optional.empty());
		when(userRepository.findByEmail("local@example.com")).thenReturn(Optional.of(local));

		assertThatThrownBy(() -> service.resolveLocalUser(new GoogleIdentity(
				"google-sub-7", "local@example.com", true)))
				.isInstanceOf(GoogleLinkRequiredException.class)
				.hasMessage(AuthGoogleService.LINK_REQUIRED_MESSAGE);

		verify(userRepository, never()).saveAndFlush(any());
		verify(userRepository, never()).updateEmail(any(), any());
		assertThat(local.getGoogleSub()).isNull();
		verify(refreshService, never()).issueRefreshToken(any());
		verify(jwtService, never()).issueAccessToken(any());
	}

	@Test
	void refreshIssuanceFailureBecomesUniformFailure() throws Exception {
		User existing = googleUser("known@example.com", "google-sub-8");
		when(userRepository.findByGoogleSub("google-sub-8"))
				.thenReturn(Optional.of(existing));
		when(refreshService.issueRefreshToken(existing.getId()))
				.thenThrow(new RuntimeException("forced issuance failure"));

		assertThatThrownBy(() -> service.login(new GoogleIdentity("google-sub-8",
				"known@example.com", true)))
				.isInstanceOf(InvalidGoogleIdentityException.class)
				.hasMessage(AuthGoogleService.INVALID_IDENTITY_MESSAGE);

		verify(jwtService, never()).issueAccessToken(any());
	}

	@Test
	void duplicateSubjectRaceAfterLossMapsToUniformFailure() throws Exception {
		User racer = googleUser("racer@example.com", "google-sub-9");
		when(userRepository.findByGoogleSub("google-sub-9")).thenReturn(Optional.empty())
				.thenReturn(Optional.of(racer));
		when(userRepository.findByEmail("racer@example.com")).thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class)))
				.thenThrow(new DataIntegrityViolationException("forced duplicate sub"));

		assertThatThrownBy(() -> service.resolveLocalUser(new GoogleIdentity(
				"google-sub-9", "racer@example.com", true)))
				.isInstanceOf(InvalidGoogleIdentityException.class)
				.hasMessage(AuthGoogleService.INVALID_IDENTITY_MESSAGE);
	}

	@Test
	void duplicateEmailRaceAfterLossMapsToConflict() {
		User racer = new User();
		when(userRepository.findByGoogleSub("google-sub-10")).thenReturn(Optional.empty());
		when(userRepository.findByEmail("racer@example.com")).thenReturn(Optional.empty())
				.thenReturn(Optional.of(racer));
		when(userRepository.saveAndFlush(any(User.class)))
				.thenThrow(new DataIntegrityViolationException("forced duplicate email"));

		assertThatThrownBy(() -> service.resolveLocalUser(new GoogleIdentity(
				"google-sub-10", "racer@example.com", true)))
				.isInstanceOf(GoogleLinkRequiredException.class)
				.hasMessage(AuthGoogleService.LINK_REQUIRED_MESSAGE);
	}

	private User googleUser(String email, String subject) throws Exception {
		User user = new User();
		user.assignGoogleIdentity(email, subject);
		withId(user, UUID.randomUUID());
		return user;
	}

	private User credentialedUser(String email) throws Exception {
		User user = new User();
		user.assignCredentials(email, "$2a$10$static-test-hash-for-google-tests-0000000000");
		withId(user, UUID.randomUUID());
		return user;
	}

	private static void withId(User user, UUID id) throws Exception {
		Field field = User.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(user, id);
	}
}
