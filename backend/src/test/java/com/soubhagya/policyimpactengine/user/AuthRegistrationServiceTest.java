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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 8A — deterministic unit tests for {@link AuthRegistrationService}.
 *
 * <p>No Spring context, no database. A real {@link BCryptPasswordEncoder}
 * proves the hash round-trip; the repository is mocked.
 */
@ExtendWith(MockitoExtension.class)
class AuthRegistrationServiceTest {

	@Mock
	private UserRepository userRepository;

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	private AuthRegistrationService service;

	@BeforeEach
	void setUp() {
		service = new AuthRegistrationService(userRepository, passwordEncoder);
	}

	@Test
	void registerNormalizesEmailAndHashesPassword() throws Exception {
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			withId(user, UUID.randomUUID());
			return user;
		});

		RegistrationResult result = service.register("  User@Example.COM ", "correct-horse-1");

		assertThat(result.email()).isEqualTo("user@example.com");
		assertThat(result.id()).isNotNull();
		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getEmail()).isEqualTo("user@example.com");
		assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("correct-horse-1");
		assertThat(saved.getValue().getPasswordHash()).doesNotContain("correct-horse-1");
		assertThat(passwordEncoder.matches("correct-horse-1", saved.getValue().getPasswordHash())).isTrue();
	}

	@Test
	void wrongPasswordDoesNotMatchStoredHash() {
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.register("user@example.com", "correct-horse-1");

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(saved.capture());
		assertThat(passwordEncoder.matches("wrong-password-2", saved.getValue().getPasswordHash())).isFalse();
	}

	@Test
	void duplicateEmailIsRejectedWithoutHashing() {
		when(userRepository.findByEmail("user@example.com"))
				.thenReturn(Optional.of(new User()));

		assertThatThrownBy(() -> service.register("USER@example.com", "correct-horse-1"))
				.isInstanceOf(DuplicateEmailException.class)
				.hasMessage("Email is already registered");

		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	@Test
	void lostInsertRaceConvergesToDuplicateEmail() {
		when(userRepository.findByEmail("user@example.com"))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(new User()));
		when(userRepository.saveAndFlush(any(User.class)))
				.thenThrow(new DataIntegrityViolationException("uq_app_user_email"));

		assertThatThrownBy(() -> service.register("user@example.com", "correct-horse-1"))
				.isInstanceOf(DuplicateEmailException.class);
	}

	@Test
	void shortPasswordIsRejected() {
		assertThatThrownBy(() -> service.register("user@example.com", "short-1"))
				.isInstanceOf(IllegalArgumentException.class);
		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	@Test
	void blankPasswordIsRejected() {
		assertThatThrownBy(() -> service.register("user@example.com", ""))
				.isInstanceOf(IllegalArgumentException.class);
		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	@Test
	void exactly72Utf8BytesAreAccepted() {
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		// 36 x U+00E9 (2 bytes each) = exactly 72 UTF-8 bytes.
		RegistrationResult result = service.register("user@example.com", "é".repeat(36));

		assertThat(result.email()).isEqualTo("user@example.com");
	}

	@Test
	void beyond72Utf8BytesIsRejectedWithoutTruncation() {
		assertThatThrownBy(() -> service.register("user@example.com", "a".repeat(73)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("72 UTF-8 bytes");
		// 37 x U+00E9 = 74 UTF-8 bytes despite only 37 characters.
		assertThatThrownBy(() -> service.register("user@example.com", "é".repeat(37)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("72 UTF-8 bytes");
		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	@Test
	void blankEmailIsRejected() {
		assertThatThrownBy(() -> service.register("   ", "correct-horse-1"))
				.isInstanceOf(IllegalArgumentException.class);
		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	private static void withId(User user, UUID id) throws Exception {
		Field field = User.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(user, id);
	}
}
