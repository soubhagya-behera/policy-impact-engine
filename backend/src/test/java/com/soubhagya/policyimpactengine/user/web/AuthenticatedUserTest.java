package com.soubhagya.policyimpactengine.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.soubhagya.policyimpactengine.user.AuthenticationRequiredException;

/**
 * Phase 8B — unit tests for the principal contract: only an
 * authenticated {@link AuthenticatedUser} principal resolves to a
 * UUID; everything else fails as 401-mapped.
 */
class AuthenticatedUserTest {

	@Test
	void recordRejectsNullUserId() {
		assertThatThrownBy(() -> new AuthenticatedUser(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void validPrincipalReturnsExactUuid() {
		UUID userId = UUID.randomUUID();
		UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(
						new AuthenticatedUser(userId), null, List.of());

		assertThat(AuthenticatedUsers.requireUserId(authentication)).isEqualTo(userId);
	}

	@Test
	void nullAuthenticationIsRejected() {
		assertThatThrownBy(() -> AuthenticatedUsers.requireUserId(null))
				.isInstanceOf(AuthenticationRequiredException.class)
				.hasMessage("Authentication is required");
	}

	@Test
	void anonymousAuthenticationIsRejected() {
		AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
				"key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

		assertThatThrownBy(() -> AuthenticatedUsers.requireUserId(anonymous))
				.isInstanceOf(AuthenticationRequiredException.class);
	}

	@Test
	void wrongPrincipalTypeIsRejected() {
		UsernamePasswordAuthenticationToken emailPrincipal =
				new UsernamePasswordAuthenticationToken("user@example.com", null, List.of());

		assertThatThrownBy(() -> AuthenticatedUsers.requireUserId(emailPrincipal))
				.isInstanceOf(AuthenticationRequiredException.class);
	}

	@Test
	void unauthenticatedTokenIsRejected() {
		UsernamePasswordAuthenticationToken unauthenticated =
				new UsernamePasswordAuthenticationToken(new AuthenticatedUser(UUID.randomUUID()), "x");

		assertThatThrownBy(() -> AuthenticatedUsers.requireUserId(unauthenticated))
				.isInstanceOf(AuthenticationRequiredException.class);
	}
}
