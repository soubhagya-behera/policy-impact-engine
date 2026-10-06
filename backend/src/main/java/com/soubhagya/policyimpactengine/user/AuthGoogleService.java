package com.soubhagya.policyimpactengine.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 18-B — Google identity to local-user resolution (see DECISIONS.md
 * ADR-037).
 *
 * <p>Policy: resolve by stable {@code google_sub} first; create a
 * Google-only local user (verified email, null password hash) when neither
 * the subject nor the normalized email exists; never merge on email alone —
 * an anonymous Google login colliding with an existing local email raises
 * {@link GoogleLinkRequiredException} with no write. Unverified or blank
 * identities fail closed with {@link InvalidGoogleIdentityException}.
 * Mirrors {@link AuthLoginService} atomicity: refresh-issuance failure
 * alone becomes the uniform identity failure and rolls the login back, so
 * no success is ever returned without a usable refresh token. Never
 * touches the security context.
 */
@Service
public class AuthGoogleService {

	static final String INVALID_IDENTITY_MESSAGE = "Invalid Google identity";
	static final String LINK_REQUIRED_MESSAGE = "An account with that email already exists";

	private final UserRepository userRepository;
	private final JwtService jwtService;
	private final AuthRefreshService refreshService;

	public AuthGoogleService(UserRepository userRepository,
			JwtService jwtService, AuthRefreshService refreshService) {
		if (userRepository == null) {
			throw new IllegalArgumentException("UserRepository must not be null");
		}
		if (jwtService == null) {
			throw new IllegalArgumentException("JwtService must not be null");
		}
		if (refreshService == null) {
			throw new IllegalArgumentException("RefreshService must not be null");
		}
		this.userRepository = userRepository;
		this.jwtService = jwtService;
		this.refreshService = refreshService;
	}

	@Transactional
	public LoginResult login(GoogleIdentity identity) {
		UUID userId = resolveLocalUser(identity);
		return issueTokens(userId);
	}

	/**
	 * Resolves the validated Google identity to the local user id without
	 * issuing any tokens. Used by the OAuth callback bridge: the single
	 * token pair is issued later, atomically with the completion-code
	 * exchange, so no orphan refresh row is ever left behind.
	 */
	@Transactional
	public UUID resolveLocalUser(GoogleIdentity identity) {
		if (identity == null || !identity.emailVerified()) {
			throw new InvalidGoogleIdentityException(INVALID_IDENTITY_MESSAGE);
		}
		String normalized = AuthRegistrationService.normalizeEmail(identity.email());
		Optional<User> bySubject = userRepository.findByGoogleSub(identity.subject());
		if (bySubject.isPresent()) {
			User existing = bySubject.get();
			if (!normalized.equals(existing.getEmail())) {
				updateGoogleEmail(existing, normalized);
			}
			return existing.getId();
		}
		Optional<User> byEmail = userRepository.findByEmail(normalized);
		if (byEmail.isPresent()) {
			throw new GoogleLinkRequiredException(LINK_REQUIRED_MESSAGE);
		}
		User user = new User();
		user.assignGoogleIdentity(normalized, identity.subject());
		try {
			return userRepository.saveAndFlush(user).getId();
		}
		catch (DataIntegrityViolationException duplicate) {
			throw resolvedDuplicate(identity.subject(), normalized, duplicate);
		}
	}

	private LoginResult issueTokens(UUID userId) {
		RefreshResult refresh;
		try {
			refresh = refreshService.issueRefreshToken(userId);
		}
		catch (RuntimeException issuanceFailure) {
			throw new InvalidGoogleIdentityException(INVALID_IDENTITY_MESSAGE);
		}
		return new LoginResult(userId, jwtService.issueAccessToken(userId),
				jwtService.accessTokenExpiresInSeconds(),
				refresh.refreshToken(), refresh.expiresInSeconds());
	}

	private RuntimeException resolvedDuplicate(String subject, String normalized,
			DataIntegrityViolationException duplicate) {
		Optional<User> raced = userRepository.findByGoogleSub(subject);
		if (raced.isPresent()) {
			return new InvalidGoogleIdentityException(INVALID_IDENTITY_MESSAGE);
		}
		if (userRepository.findByEmail(normalized).isPresent()) {
			return new GoogleLinkRequiredException(LINK_REQUIRED_MESSAGE);
		}
		return duplicate;
	}

	/**
	 * Updates a Google-linked account's email after a verified provider
	 * change, failing safely on collision: the other account is never
	 * overwritten. The email column is insert-only by mapping, so the
	 * update goes through an explicit query.
	 */
	private void updateGoogleEmail(User existing, String normalized) {
		if (userRepository.findByEmail(normalized).isPresent()) {
			throw new InvalidGoogleIdentityException(INVALID_IDENTITY_MESSAGE);
		}
		userRepository.updateEmail(existing.getId(), normalized);
	}
}
