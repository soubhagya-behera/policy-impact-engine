package com.soubhagya.policyimpactengine.user;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 8A — account registration over the nullable credential
 * transition (see DECISIONS.md ADR-017).
 *
 * <p>Flow: normalize email (trim + lowercase) → reject duplicates →
 * BCrypt-hash the raw password → persist → return the safe result
 * (id + email). Raw passwords are never persisted or logged. No login,
 * no tokens, no roles.
 */
@Service
public class AuthRegistrationService {

	static final int PASSWORD_MIN_LENGTH = 8;
	static final int PASSWORD_MAX_UTF8_BYTES = 72;
	static final int EMAIL_MAX_LENGTH = 254;

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	public AuthRegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
		if (userRepository == null) {
			throw new IllegalArgumentException("UserRepository must not be null");
		}
		if (passwordEncoder == null) {
			throw new IllegalArgumentException("PasswordEncoder must not be null");
		}
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public RegistrationResult register(String email, String rawPassword) {
		String normalized = normalizeEmail(email);
		validatePassword(rawPassword);
		if (userRepository.findByEmail(normalized).isPresent()) {
			throw new DuplicateEmailException("Email is already registered");
		}
		User user = new User();
		user.assignCredentials(normalized, passwordEncoder.encode(rawPassword));
		try {
			User saved = userRepository.saveAndFlush(user);
			return new RegistrationResult(saved.getId(), saved.getEmail());
		}
		catch (DataIntegrityViolationException duplicate) {
			throw convertedDuplicate(normalized, duplicate);
		}
	}

	@Transactional(readOnly = true)
	public User getUser(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
	}

	static String normalizeEmail(String email) {
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("Email must not be blank");
		}
		String normalized = email.strip().toLowerCase(Locale.ROOT);
		if (normalized.length() > EMAIL_MAX_LENGTH) {
			throw new IllegalArgumentException("Email must be at most 254 characters");
		}
		return normalized;
	}

	private static void validatePassword(String rawPassword) {
		if (rawPassword == null || rawPassword.isEmpty()) {
			throw new IllegalArgumentException("Password must not be blank");
		}
		if (rawPassword.length() < PASSWORD_MIN_LENGTH) {
			throw new IllegalArgumentException("Password must be at least 8 characters");
		}
		if (rawPassword.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_UTF8_BYTES) {
			throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes");
		}
	}

	private RuntimeException convertedDuplicate(String normalized, DataIntegrityViolationException duplicate) {
		return userRepository.findByEmail(normalized)
				.map(existing -> (RuntimeException) new DuplicateEmailException("Email is already registered"))
				.orElse(duplicate);
	}
}
