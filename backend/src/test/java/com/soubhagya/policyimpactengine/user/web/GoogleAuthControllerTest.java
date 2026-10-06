package com.soubhagya.policyimpactengine.user.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.user.GoogleCompletionService;
import com.soubhagya.policyimpactengine.user.GoogleLinkRequiredException;
import com.soubhagya.policyimpactengine.user.InvalidGoogleIdentityException;
import com.soubhagya.policyimpactengine.user.LoginResult;

/**
 * Phase 18-B — web-layer tests for {@link GoogleAuthController} (see
 * DECISIONS.md ADR-037). Services are mocked; the completion response
 * reuses exactly the login token shape, and failures keep the RFC 7807
 * problem convention. Security filters are disabled: the filter chain
 * is covered with filters enabled in the integration tests.
 */
@WebMvcTest(GoogleAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class GoogleAuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private GoogleCompletionService completionService;

	// Phase 18-B: the controller emits the Google login audit
	// post-commit; mocked so this slice stays isolated from the chain.
	@MockitoBean
	private AuditService auditService;

	// Satisfies SecurityConfig wiring in this slice. Filters stay
	// disabled, so the mocks never execute.
	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	@MockitoBean
	private com.soubhagya.policyimpactengine.common.ratelimit.web.RateLimitFilter rateLimitFilter;

	@MockitoBean
	private org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

	@MockitoBean
	private CookieAuthorizationRequestRepository cookieAuthorizationRequestRepository;

	@MockitoBean
	private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

	@MockitoBean
	private GoogleOAuthProperties googleOAuthProperties;

	@Test
	void startRedirectsIntoOAuthAuthorizationEntryPoint() throws Exception {
		mockMvc.perform(get("/api/v1/auth/google/start"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "/oauth2/authorization/google"));

		verifyNoInteractions(completionService);
	}

	@Test
	void completeReturnsStandardTokenShapeAndAudits() throws Exception {
		UUID userId = UUID.randomUUID();
		when(completionService.complete("one-time-code")).thenReturn(
				new LoginResult(userId, "issued-access", 900L,
						"issued-refresh", 2_592_000L));

		mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"code":"one-time-code"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("issued-access"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.refreshToken").value("issued-refresh"))
				.andExpect(jsonPath("$.refreshExpiresIn").value(2_592_000))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.email").doesNotExist())
				.andExpect(jsonPath("$.code").doesNotExist());

		verify(completionService).complete("one-time-code");
		verify(auditService).append(
				org.mockito.ArgumentMatchers.eq(userId),
				org.mockito.ArgumentMatchers.eq(AuditEventType.AUTH_GOOGLE_LOGIN_SUCCEEDED),
				org.mockito.ArgumentMatchers.eq("USER"),
				org.mockito.ArgumentMatchers.eq(userId),
				org.mockito.ArgumentMatchers.any(AuditMetadata.class),
				org.mockito.ArgumentMatchers.isNull());
	}

	@Test
	void unknownCodeReturns401ProblemWithoutAudit() throws Exception {
		when(completionService.complete("stale-code"))
				.thenThrow(new InvalidGoogleIdentityException("Invalid Google identity"));

		mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"code":"stale-code"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid Google identity"));

		verifyNoInteractions(auditService);
	}

	@Test
	void linkingConflictReturns409ProblemWithoutAudit() throws Exception {
		when(completionService.complete("linked-code"))
				.thenThrow(new GoogleLinkRequiredException(
						"An account with that email already exists"));

		mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"code":"linked-code"}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Already registered"));

		verifyNoInteractions(auditService);
	}

	@Test
	void blankCodeReturnsValidation400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"code":""}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Validation failed"));

		verifyNoInteractions(completionService, auditService);
	}
}
