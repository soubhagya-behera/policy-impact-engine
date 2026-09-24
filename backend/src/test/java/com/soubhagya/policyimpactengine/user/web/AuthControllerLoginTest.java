package com.soubhagya.policyimpactengine.user.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import com.soubhagya.policyimpactengine.user.AuthLoginService;
import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.InvalidCredentialsException;
import com.soubhagya.policyimpactengine.user.LoginResult;

/**
 * Phase 8B — web-layer tests for login. Services are mocked; response
 * shape, status codes, and RFC 7807 problem responses are verified
 * here. Security filters are disabled: the filter itself is covered
 * with filters enabled in {@link JwtAuthenticationIntegrationTest}.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerLoginTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthRegistrationService registrationService;

	@MockitoBean
	private AuthLoginService loginService;

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
	void loginReturnsBearerTokenResponseWithoutCredentialMaterial() throws Exception {
		UUID userId = UUID.randomUUID();
		when(loginService.login("user@example.com", "correct-horse-1"))
				.thenReturn(new LoginResult(userId, "issued-token", 900L));

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"user@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("issued-token"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.email").doesNotExist());

		verify(loginService).login("user@example.com", "correct-horse-1");
	}

	@Test
	void badCredentialsReturn401Problem() throws Exception {
		when(loginService.login("user@example.com", "wrong-password-2"))
				.thenThrow(new InvalidCredentialsException("Invalid email or password"));

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"user@example.com","password":"wrong-password-2"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"))
				.andExpect(jsonPath("$.detail").value("Invalid email or password"));
	}

	@Test
	void invalidEmailReturnsValidation400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"not-an-email","password":"correct-horse-1"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Validation failed"))
				.andExpect(jsonPath("$.errors.email").exists());

		verifyNoInteractions(loginService);
	}
}
