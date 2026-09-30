package com.soubhagya.policyimpactengine.user.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.user.AuthLoginService;
import com.soubhagya.policyimpactengine.user.AuthLogoutService;
import com.soubhagya.policyimpactengine.user.AuthRefreshService;
import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.user.LogoutResult;

/**
 * Phase 15-A/2 — web-layer tests for logout (see DECISIONS.md ADR-031).
 * Services are mocked; delegation, 204 shapes, validation, principal
 * resolution, audit emission placement, and RFC 7807 problem responses
 * are verified here. Security filters are disabled: anonymous exposure
 * and authentication are covered by the endpoint integration tests.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerLogoutTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthRegistrationService registrationService;

	@MockitoBean
	private AuthLoginService loginService;

	@MockitoBean
	private AuthRefreshService refreshService;

	@MockitoBean
	private AuthLogoutService logoutService;

	@MockitoBean
	private JwtService jwtService;

	@MockitoBean
	private com.soubhagya.policyimpactengine.audit.application.AuditService auditService;

	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	@MockitoBean
	private com.soubhagya.policyimpactengine.common.ratelimit.web.RateLimitFilter rateLimitFilter;

	@MockitoBean
	private org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

	@Test
	void logoutRevokingTokenReturns204EmptyBodyAndEmitsAudit() throws Exception {
		UUID userId = UUID.randomUUID();
		when(logoutService.logoutSingle("presented-refresh-token"))
				.thenReturn(LogoutResult.revoked(userId));

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"presented-refresh-token"}
								"""))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(logoutService).logoutSingle("presented-refresh-token");
		verify(auditService).append(eq(userId), eq(AuditEventType.AUTH_LOGOUT_SUCCEEDED),
				eq("USER"), eq(userId), any(AuditMetadata.class), eq(null));
	}

	@Test
	void logoutNoopReturns204WithoutAudit() throws Exception {
		when(logoutService.logoutSingle("stale-token")).thenReturn(LogoutResult.noop());

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"stale-token"}
								"""))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(logoutService).logoutSingle("stale-token");
		verifyNoInteractions(auditService);
	}

	@Test
	void missingAndBlankLogoutTokenReturn400WithoutServiceCall() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"   "}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"));

		verifyNoInteractions(logoutService);
		verifyNoInteractions(auditService);
	}

	@Test
	void logoutIgnoresExtraIdentityFields() throws Exception {
		UUID ownerId = UUID.randomUUID();
		when(logoutService.logoutSingle("presented-token"))
				.thenReturn(LogoutResult.revoked(ownerId));

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"presented-token","userId":"%s","email":"intruder@example.com"}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isNoContent());

		verify(logoutService).logoutSingle("presented-token");
	}

	@Test
	void logoutAllDelegatesWithPrincipalUuidAndEmitsAudit() throws Exception {
		UUID userId = UUID.randomUUID();
		Authentication authentication = new UsernamePasswordAuthenticationToken(
				new AuthenticatedUser(userId), null, List.of());
		when(logoutService.logoutAll(userId)).thenReturn(LogoutResult.revoked(userId));

		mockMvc.perform(post("/api/v1/auth/logout-all").principal(authentication))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(logoutService).logoutAll(userId);
		verify(auditService).append(eq(userId), eq(AuditEventType.AUTH_LOGOUT_ALL_SUCCEEDED),
				eq("USER"), eq(userId), any(AuditMetadata.class), eq(null));
	}

	@Test
	void logoutAllZeroLiveReturns204WithoutAudit() throws Exception {
		UUID userId = UUID.randomUUID();
		Authentication authentication = new UsernamePasswordAuthenticationToken(
				new AuthenticatedUser(userId), null, List.of());
		when(logoutService.logoutAll(userId)).thenReturn(LogoutResult.noop());

		mockMvc.perform(post("/api/v1/auth/logout-all").principal(authentication))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(logoutService).logoutAll(userId);
		verifyNoInteractions(auditService);
	}
}
