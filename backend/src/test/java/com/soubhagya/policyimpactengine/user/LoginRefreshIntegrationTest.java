package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 14-A/3a — Testcontainers proof that login atomically issues a
 * usable refresh token (see DECISIONS.md ADR-029 §3).
 *
 * <p>Successful logins persist exactly one digest row each; the raw
 * value returned matches the stored SHA-256 digest and the access
 * token still parses to the user. Failed credentials and issuance
 * failures both yield the uniform credential failure with nothing
 * persisted. Service calls emit no audit events (the controller
 * owns emission), so no audit cleanup is needed here.
 */
@SpringBootTest
@Testcontainers
class LoginRefreshIntegrationTest {

	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD = "correct-horse-1";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private AuthLoginService loginService;

	@Autowired
	private AuthRegistrationService registrationService;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@BeforeEach
	void clean() {
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void successfulLoginReturnsUsableAccessAndRefreshToken() {
		registrationService.register(EMAIL, PASSWORD);

		LoginResult result = loginService.login(EMAIL, PASSWORD);

		assertThat(result.accessToken()).isNotBlank();
		assertThat(jwtService.parseUserId(result.accessToken()))
				.isEqualTo(result.userId());
		assertThat(result.expiresInSeconds()).isEqualTo(900L);
		assertThat(result.refreshToken()).hasSize(43);
		assertThat(result.refreshExpiresInSeconds()).isEqualTo(2_592_000L);
		assertThat(refreshTokenRepository.count()).isEqualTo(1);
		RefreshToken row = refreshTokenRepository.findAll().get(0);
		assertThat(row.getTokenHash())
				.isEqualTo(AuthRefreshService.sha256Hex(result.refreshToken()));
		assertThat(row.getUser().getId()).isEqualTo(result.userId());
		assertThat(row.getRevokedAt()).isNull();
		assertThat(row.getReplacedByTokenHash()).isNull();
	}

	@Test
	void secondLoginCreatesSecondRefreshRow() {
		registrationService.register(EMAIL, PASSWORD);
		LoginResult first = loginService.login(EMAIL, PASSWORD);
		LoginResult second = loginService.login(EMAIL, PASSWORD);

		assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
		assertThat(refreshTokenRepository.count()).isEqualTo(2);
		assertThat(refreshTokenRepository.findAll())
				.extracting(RefreshToken::getTokenHash)
				.containsExactlyInAnyOrder(
						AuthRefreshService.sha256Hex(first.refreshToken()),
						AuthRefreshService.sha256Hex(second.refreshToken()));
	}

	@Test
	void failedCredentialsCreateNoRefreshRow() {
		registrationService.register(EMAIL, PASSWORD);

		assertThatThrownBy(() -> loginService.login(EMAIL, "wrong-password-2"))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);
		assertThatThrownBy(() -> loginService.login("absent@example.com", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);

		assertThat(refreshTokenRepository.count()).isZero();
	}

	@Test
	void issuanceFailureProducesUniformFailureWithoutRow() {
		registrationService.register(EMAIL, PASSWORD);
		AuthRefreshService failingRefresh = mock(AuthRefreshService.class);
		when(failingRefresh.issueRefreshToken(any()))
				.thenThrow(new RuntimeException("forced persistence failure"));
		AuthLoginService failingLogin = new AuthLoginService(userRepository,
				new BCryptPasswordEncoder(), jwtService, failingRefresh);

		assertThatThrownBy(() -> failingLogin.login(EMAIL, PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage(AuthLoginService.INVALID_CREDENTIALS_MESSAGE);

		assertThat(refreshTokenRepository.count()).isZero();
	}
}
