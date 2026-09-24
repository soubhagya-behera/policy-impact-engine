package com.soubhagya.policyimpactengine.notification.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.notification.application.NotificationNotFoundException;
import com.soubhagya.policyimpactengine.notification.application.NotificationService;
import com.soubhagya.policyimpactengine.notification.web.dto.NotificationResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 10B-2B — web-layer tests for the authenticated notification
 * feed. The application service is mocked; principal resolution,
 * delegation, status codes, response shape, and RFC 7807 problem
 * responses are verified here. Security filters are disabled: the
 * authenticated principal is supplied directly, exactly as the
 * enabled filter chain would publish it.
 */
@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private NotificationService service;

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

	private UUID userId;
	private Authentication authentication;

	@BeforeEach
	void authenticate() {
		userId = UUID.randomUUID();
		authentication = new UsernamePasswordAuthenticationToken(
				new AuthenticatedUser(userId), null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void listDelegatesWithPrincipalUuid() throws Exception {
		NotificationResponse response = sampleResponse(false);
		when(service.listNotificationResponsesPaged(userId, 0, 20)).thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/notifications").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(response.id().toString()))
				.andExpect(jsonPath("$[0].assessmentId").value(response.assessmentId().toString()))
				.andExpect(jsonPath("$[0].policyId").value(response.policyId().toString()))
				.andExpect(jsonPath("$[0].versionNumber").value(2))
				.andExpect(jsonPath("$[0].read").value(false));

		verify(service).listNotificationResponsesPaged(userId, 0, 20);
	}

	@Test
	void listUnreadDelegatesWithPrincipalUuid() throws Exception {
		NotificationResponse response = sampleResponse(false);
		when(service.listUnreadNotificationResponsesPaged(userId, 0, 20)).thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/notifications/unread").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(response.id().toString()));

		verify(service).listUnreadNotificationResponsesPaged(userId, 0, 20);
	}

	@Test
	void markReadDelegatesWithPrincipalUuidAndNotificationId() throws Exception {
		NotificationResponse response = sampleResponse(true);
		UUID notificationId = response.id();
		when(service.markReadResponse(userId, notificationId)).thenReturn(response);

		mockMvc.perform(post("/api/v1/me/notifications/{notificationId}/read", notificationId)
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(notificationId.toString()))
				.andExpect(jsonPath("$.read").value(true))
				.andExpect(jsonPath("$.readAt").exists());

		verify(service).markReadResponse(userId, notificationId);
	}

	@Test
	void responseContainsOnlyApprovedFields() throws Exception {
		NotificationResponse response = sampleResponse(true);
		when(service.listNotificationResponsesPaged(userId, 0, 20)).thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/notifications").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").exists())
				.andExpect(jsonPath("$[0].assessmentId").exists())
				.andExpect(jsonPath("$[0].policyId").exists())
				.andExpect(jsonPath("$[0].versionNumber").exists())
				.andExpect(jsonPath("$[0].createdAt").exists())
				.andExpect(jsonPath("$[0].readAt").exists())
				.andExpect(jsonPath("$[0].read").exists())
				.andExpect(jsonPath("$[0].passwordHash").doesNotExist())
				.andExpect(jsonPath("$[0].password").doesNotExist())
				.andExpect(jsonPath("$[0].email").doesNotExist())
				.andExpect(jsonPath("$[0].accessToken").doesNotExist())
				.andExpect(jsonPath("$[0].token").doesNotExist())
				.andExpect(jsonPath("$[0].user").doesNotExist())
				.andExpect(jsonPath("$[0].score").doesNotExist())
				.andExpect(jsonPath("$[0].assessment").doesNotExist());
	}

	@Test
	void unknownNotificationReturns404Problem() throws Exception {
		UUID notificationId = UUID.randomUUID();
		when(service.markReadResponse(userId, notificationId))
				.thenThrow(new NotificationNotFoundException("Notification not found"));

		mockMvc.perform(post("/api/v1/me/notifications/{notificationId}/read", notificationId)
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail").value("Notification not found"));
	}

	@Test
	void malformedNotificationUuidReturns400Problem() throws Exception {
		mockMvc.perform(post("/api/v1/me/notifications/{notificationId}/read", "not-a-uuid")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(service);
	}

	@Test
	void clientSuppliedUserIdCannotOverridePrincipal() throws Exception {
		UUID foreignId = UUID.randomUUID();
		NotificationResponse response = sampleResponse(false);
		when(service.listNotificationResponsesPaged(userId, 0, 20)).thenReturn(List.of(response));
		when(service.listUnreadNotificationResponsesPaged(userId, 0, 20)).thenReturn(List.of(response));
		when(service.markReadResponse(userId, response.id())).thenReturn(response);

		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/me/notifications/unread")
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/me/notifications/{notificationId}/read", response.id())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"" + foreignId + "\"}")
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		verify(service).listNotificationResponsesPaged(userId, 0, 20);
		verify(service).listUnreadNotificationResponsesPaged(userId, 0, 20);
		verify(service).markReadResponse(userId, response.id());
	}

	@Test
	void listDefaultsToFirstPageOfTwenty() throws Exception {
		NotificationResponse response = sampleResponse(false);
		when(service.listNotificationResponsesPaged(userId, 0, 20))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/notifications").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1));

		verify(service).listNotificationResponsesPaged(userId, 0, 20);
	}

	@Test
	void listAcceptsExplicitPageAndSize() throws Exception {
		NotificationResponse response = sampleResponse(false);
		when(service.listNotificationResponsesPaged(userId, 2, 5))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("page", "2")
						.queryParam("size", "5")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		verify(service).listNotificationResponsesPaged(userId, 2, 5);
	}

	@Test
	void listAcceptsMaximumSize() throws Exception {
		when(service.listNotificationResponsesPaged(userId, 0, 100))
				.thenReturn(List.of());

		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("size", "100")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		verify(service).listNotificationResponsesPaged(userId, 0, 100);
	}

	@Test
	void listRejectsSizeAboveMaximum() throws Exception {
		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("size", "101")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid request"));

		verifyNoInteractions(service);
	}

	@Test
	void listRejectsZeroSizeAndNegativePage() throws Exception {
		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("size", "0")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("page", "-1")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		verifyNoInteractions(service);
	}

	@Test
	void listRejectsMalformedPageWithNeutralMessage() throws Exception {
		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("page", "not-a-number")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"))
				.andExpect(jsonPath("$.detail").value("Invalid value for 'page'"));

		verifyNoInteractions(service);
	}

	@Test
	void listRejectsOverflowingSize() throws Exception {
		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("size", "9999999999")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		verifyNoInteractions(service);
	}

	@Test
	void duplicatePageParametersUseFirstValue() throws Exception {
		NotificationResponse response = sampleResponse(false);
		when(service.listNotificationResponsesPaged(userId, 0, 5))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("page", "0")
						.queryParam("page", "2")
						.queryParam("size", "5")
						.principal(authentication))
				.andExpect(status().isOk());

		verify(service).listNotificationResponsesPaged(userId, 0, 5);
	}

	@Test
	void unauthenticatedListWithInvalidPaginationReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("page", "-1")
						.queryParam("size", "500"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(service);
	}

	@Test
	void unreadDefaultsToFirstPageOfTwentyAndRejectsInvalidSize() throws Exception {
		NotificationResponse response = sampleResponse(false);
		when(service.listUnreadNotificationResponsesPaged(userId, 0, 20))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/me/notifications/unread").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		verify(service).listUnreadNotificationResponsesPaged(userId, 0, 20);

		mockMvc.perform(get("/api/v1/me/notifications/unread")
						.queryParam("size", "101")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));
	}

	private NotificationResponse sampleResponse(boolean read) {
		Instant createdAt = Instant.parse("2026-09-18T10:00:00Z");
		return new NotificationResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				2,
				createdAt,
				read ? createdAt.plusSeconds(3600) : null,
				read);
	}

}
