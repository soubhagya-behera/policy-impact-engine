package com.soubhagya.policyimpactengine.user.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.soubhagya.policyimpactengine.user.AuthLoginService;
import com.soubhagya.policyimpactengine.user.AuthRefreshService;
import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.InvalidRefreshTokenException;
import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.user.RefreshResult;

/**
 * Phase 14-A/3b — web-layer tests for refresh rotation. Services are
 * mocked; response shape, status codes, validation, and RFC 7807 problem
 * responses are verified here. Security filters are disabled: anonymous
 * exposure with filters enabled is covered by the endpoint integration
 * test.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerRefreshTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthRegistrationService registrationService;

	@MockitoBean
	private AuthLoginService loginService;

	@MockitoBean
	private AuthRefreshService refreshService;

	@MockitoBean
	private JwtService jwtService;

	// Phase 11C: AuthController emits audit events post-commit; mocked
	// so this slice stays isolated from the audit chain.
	@MockitoBean
	private com.soubhagya.policyimpactengine.audit.application.AuditService auditService;

	// Phase 8B: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	// Phase 13-C: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private com.soubhagya.policyimpactengine.common.ratelimit.web.RateLimitFilter rateLimitFilter;

	// Phase 13-D: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

	@Test
	void successfulRefreshReturnsNewAccessAndRefreshTokens() throws Exception {
		UUID userId = UUID.randomUUID();
		RefreshResult rotated = new RefreshResult(userId, "new-refresh-token",
				Instant.parse("2026-10-26T10:00:00Z"), 2_592_000L);
		when(refreshService.rotate("presented-refresh-token")).thenReturn(rotated);
		when(jwtService.issueAccessToken(userId)).thenReturn("new-access-token");
		when(jwtService.accessTokenExpiresInSeconds()).thenReturn(900L);

		MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"presented-refresh-token"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("new-access-token"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.refreshToken").value("new-refresh-token"))
				.andExpect(jsonPath("$.refreshExpiresIn").value(2_592_000))
				.andExpect(jsonPath("$.tokenHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.userId").doesNotExist())
				.andReturn();

		String body = result.getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(body).doesNotContain("presented-refresh-token");
		verify(refreshService).rotate("presented-refresh-token");
		verify(jwtService).issueAccessToken(userId);
	}

	@Test
	void invalidRefreshTokenReturns401Problem() throws Exception {
		when(refreshService.rotate(any()))
				.thenThrow(new InvalidRefreshTokenException("Invalid refresh token"));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"unknown-token"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid refresh token"));

		verify(jwtService, never()).issueAccessToken(any());
	}

	@Test
	void missingAndBlankRefreshTokenReturn400WithoutServiceCall() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":""}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"   "}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		verifyNoInteractions(refreshService);
		verifyNoInteractions(jwtService);
	}

	@Test
	void extraIdentityFieldsCannotOverrideRotationOwner() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID intruderId = UUID.randomUUID();
		RefreshResult rotated = new RefreshResult(ownerId, "rotated-refresh",
				Instant.parse("2026-10-26T10:00:00Z"), 2_592_000L);
		when(refreshService.rotate("presented-refresh-token")).thenReturn(rotated);
		when(jwtService.issueAccessToken(ownerId)).thenReturn("owner-access-token");
		when(jwtService.accessTokenExpiresInSeconds()).thenReturn(900L);

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"presented-refresh-token","userId":"%s","email":"intruder@example.com","username":"intruder"}
								""".formatted(intruderId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("owner-access-token"))
				.andExpect(jsonPath("$.refreshToken").value("rotated-refresh"));

		verify(refreshService).rotate("presented-refresh-token");
		verify(jwtService).issueAccessToken(ownerId);
		verify(jwtService, never()).issueAccessToken(intruderId);
	}
}
