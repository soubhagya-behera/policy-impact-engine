package com.soubhagya.policyimpactengine.user;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 8B — credential verification and access-token issuance (see
 * DECISIONS.md ADR-018).
 *
 * <p>Flow: normalize email with the existing 8A routine → look the
 * user up → verify the BCrypt hash → issue a JWT for the User UUID.
 * Unknown emails, null hashes (legacy rows cannot log in), wrong
 * passwords, and out-of-policy password lengths all collapse to the
 * same {@link InvalidCredentialsException} with one fixed message, so
 * email existence and password state never leak. Unknown-email
 * attempts additionally run a BCrypt match against the static dummy
 * hash below (timing equalization only — it is not a secret and the
 * taken branch is never exposed).
 */
@Service
public class AuthLoginService {

	static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

	/**
	 * Timing-equalization hash for unknown-email attempts. A real BCrypt
	 * hash of a throwaway password, committed so every login attempt
	 * costs one hash verification. Not a secret; never used as a
	 * credential.
	 */
	static final String DUMMY_BCRYPT_HASH = "$2a$10$Q4VSpTjWMB1/MMW1ztfAt.VBUKrrUefgDLTB1Gd47zBUomgtavEbW";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	public AuthLoginService(UserRepository userRepository, PasswordEncoder passwordEncoder,
			JwtService jwtService) {
		if (userRepository == null) {
			throw new IllegalArgumentException("UserRepository must not be null");
		}
		if (passwordEncoder == null) {
			throw new IllegalArgumentException("PasswordEncoder must not be null");
		}
		if (jwtService == null) {
			throw new IllegalArgumentException("JwtService must not be null");
		}
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	@Transactional(readOnly = true)
	public LoginResult login(String email, String rawPassword) {
		String normalized = AuthRegistrationService.normalizeEmail(email);
		if (!passwordLengthPolicySatisfied(rawPassword)) {
			throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
		}
		Optional<User> user = userRepository.findByEmail(normalized);
		String hash = user.map(User::getPasswordHash).orElse(null);
		boolean matches = hash != null && passwordEncoder.matches(rawPassword, hash);
		if (!matches) {
			if (hash == null) {
				passwordEncoder.matches(rawPassword, DUMMY_BCRYPT_HASH);
			}
			throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
		}
		UUID userId = user.orElseThrow().getId();
		return new LoginResult(userId, jwtService.issueAccessToken(userId),
				jwtService.accessTokenExpiresInSeconds());
	}

	private static boolean passwordLengthPolicySatisfied(String rawPassword) {
		if (rawPassword == null || rawPassword.isEmpty()) {
			return false;
		}
		if (rawPassword.length() < AuthRegistrationService.PASSWORD_MIN_LENGTH) {
			return false;
		}
		return rawPassword.getBytes(StandardCharsets.UTF_8).length
				<= AuthRegistrationService.PASSWORD_MAX_UTF8_BYTES;
	}
}
