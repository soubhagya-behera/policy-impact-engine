package com.soubhagya.policyimpactengine.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
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

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEvent;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.web.dto.AuditEventResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 11D — web-layer tests for the authenticated audit feed. The
 * application service is mocked; delegation, principal resolution,
 * the exact eight-field shape, and RFC 7807 problem responses are
 * verified here. Security filters are disabled: the filter chain
 * itself is covered with filters enabled elsewhere.
 */
@WebMvcTest(AuditEventController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuditEventControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuditService service;

	// Satisfies SecurityConfig wiring in this slice. Filters stay
	// disabled, so the mock never executes.
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
	void listDelegatesAndReturnsExactEightFieldShape() throws Exception {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID resource = UUID.randomUUID();
		Instant at = Instant.parse("2026-09-18T10:00:00Z");
		when(service.listAuditEventResponsesPaged(userId, 0, 20)).thenReturn(List.of(
				new AuditEventResponse(first, at, "AUTH_USER_REGISTERED", "USER", resource,
						"{}", null, "a".repeat(64)),
				new AuditEventResponse(second, at, "AUTH_LOGIN_SUCCEEDED", null, null,
						null, "a".repeat(64), "b".repeat(64))));

		mockMvc.perform(get("/api/v1/me/audit-events").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(first.toString()))
				.andExpect(jsonPath("$[0].occurredAt").value("2026-09-18T10:00:00Z"))
				.andExpect(jsonPath("$[0].eventType").value("AUTH_USER_REGISTERED"))
				.andExpect(jsonPath("$[0].resourceType").value("USER"))
				.andExpect(jsonPath("$[0].resourceId").value(resource.toString()))
				.andExpect(jsonPath("$[0].metadata").value("{}"))
				.andExpect(jsonPath("$[0].prevHash").value(nullValue()))
				.andExpect(jsonPath("$[0].eventHash").value("a".repeat(64)))
				.andExpect(jsonPath("$[1].metadata").value(nullValue()))
				.andExpect(jsonPath("$[1].resourceType").value(nullValue()))
				.andExpect(jsonPath("$[1].actorUserId").doesNotExist())
				.andExpect(jsonPath("$[1].actor_user_id").doesNotExist());

		verify(service).listAuditEventResponsesPaged(userId, 0, 20);
	}

	@Test
	void emptyFeedReturnsEmptyArray() throws Exception {
		when(service.listAuditEventResponsesPaged(userId, 0, 20)).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/me/audit-events").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));

		verify(service).listAuditEventResponsesPaged(userId, 0, 20);
	}

	@Test
	void queryUserIdCannotChangeOwnership() throws Exception {
		when(service.listAuditEventResponsesPaged(userId, 0, 20)).thenReturn(List.of());
		UUID other = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/me/audit-events").queryParam("userId", other.toString())
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));

		verify(service).listAuditEventResponsesPaged(userId, 0, 20);
	}

	@Test
	void serviceFailurePropagatesAsProblem() throws Exception {
		when(service.listAuditEventResponsesPaged(userId, 0, 20))
				.thenThrow(new NoSuchElementException("gone"));

		mockMvc.perform(get("/api/v1/me/audit-events").principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void missingPrincipalReturnsUnauthenticatedProblem() throws Exception {
		SecurityContextHolder.clearContext();

		mockMvc.perform(get("/api/v1/me/audit-events"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void listDefaultsToFirstPageOfTwenty() throws Exception {
		when(service.listAuditEventResponsesPaged(userId, 0, 20))
				.thenReturn(List.of(sampleAuditResponse()));

		mockMvc.perform(get("/api/v1/me/audit-events").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(1));

		verify(service).listAuditEventResponsesPaged(userId, 0, 20);
	}

	@Test
	void listAcceptsExplicitPageAndSize() throws Exception {
		when(service.listAuditEventResponsesPaged(userId, 2, 5))
				.thenReturn(List.of(sampleAuditResponse()));

		mockMvc.perform(get("/api/v1/me/audit-events")
						.queryParam("page", "2")
						.queryParam("size", "5")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		verify(service).listAuditEventResponsesPaged(userId, 2, 5);
	}

	@Test
	void listRejectsSizeAboveMaximumZeroSizeAndNegativePage() throws Exception {
		mockMvc.perform(get("/api/v1/me/audit-events")
						.queryParam("size", "101")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/me/audit-events")
						.queryParam("size", "0")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/me/audit-events")
						.queryParam("page", "-1")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		verifyNoInteractions(service);
	}

	@Test
	void listRejectsMalformedPageWithNeutralMessage() throws Exception {
		mockMvc.perform(get("/api/v1/me/audit-events")
						.queryParam("page", "first")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"))
				.andExpect(jsonPath("$.detail").value("Invalid value for 'page'"));

		verifyNoInteractions(service);
	}

	@Test
	void unauthenticatedListWithInvalidPaginationReturns401() throws Exception {
		SecurityContextHolder.clearContext();

		mockMvc.perform(get("/api/v1/me/audit-events")
						.queryParam("page", "-1")
						.queryParam("size", "500"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		verifyNoInteractions(service);
	}

	private AuditEventResponse sampleAuditResponse() {
		return new AuditEventResponse(UUID.randomUUID(),
				Instant.parse("2026-09-18T10:00:00Z"), "AUTH_USER_REGISTERED", "USER",
				UUID.randomUUID(), "{}", null, "a".repeat(64));
	}

	@Test
	void responseMappingCopiesPersistedValuesVerbatim() {
		UUID resource = UUID.randomUUID();
		Instant at = Instant.parse("2026-09-18T10:00:00.123456Z");
		String metadata = "{\"conceptCode\":\"LOCATION\",\"oldSensitivity\":null,"
				+ "\"newSensitivity\":5}";
		AuditEvent event = new AuditEvent(null, AuditEventType.PRIVACY_PREFERENCE_UPSERTED,
				"USER", resource, at, metadata, "a".repeat(64), "b".repeat(64));

		AuditEventResponse response = AuditEventResponse.from(event);

		assertThat(response.id()).isEqualTo(event.getId());
		assertThat(response.occurredAt()).isEqualTo(at);
		assertThat(response.eventType()).isEqualTo("PRIVACY_PREFERENCE_UPSERTED");
		assertThat(response.resourceType()).isEqualTo("USER");
		assertThat(response.resourceId()).isEqualTo(resource);
		assertThat(response.metadata()).isSameAs(metadata);
		assertThat(response.prevHash()).isEqualTo("a".repeat(64));
		assertThat(response.eventHash()).isEqualTo("b".repeat(64));
		assertThatThrownBy(() -> AuditEventResponse.from(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
