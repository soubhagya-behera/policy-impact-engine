package com.soubhagya.policyimpactengine.user.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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

import com.soubhagya.policyimpactengine.user.UserPrivacyPreferenceService;
import com.soubhagya.policyimpactengine.user.web.dto.PrivacyPreferenceResponse;

/**
 * Web-layer tests for the authenticated privacy-preference API. The
 * application service is mocked; principal resolution, delegation,
 * Bean Validation, status codes, response shape, and RFC 7807
 * problem responses are verified here. Security filters are
 * disabled: the authenticated principal is supplied directly,
 * exactly as the enabled filter chain would publish it.
 */
@WebMvcTest(PrivacyPreferenceController.class)
@AutoConfigureMockMvc(addFilters = false)
class PrivacyPreferenceControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserPrivacyPreferenceService service;

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
	void getDelegatesWithPrincipalUuid() throws Exception {
		List<PrivacyPreferenceResponse> responses = List.of(
				new PrivacyPreferenceResponse("LOCATION", "Location", 3, false),
				new PrivacyPreferenceResponse("COOKIES", "Cookies", 5, true));
		when(service.getPreferences(userId)).thenReturn(responses);

		mockMvc.perform(get("/api/v1/me/privacy-preferences").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].conceptCode").value("LOCATION"))
				.andExpect(jsonPath("$[0].label").value("Location"))
				.andExpect(jsonPath("$[0].effectiveSensitivity").value(3))
				.andExpect(jsonPath("$[0].explicit").value(false))
				.andExpect(jsonPath("$[1].explicit").value(true));

		verify(service).getPreferences(userId);
	}

	@Test
	void putDelegatesWithPrincipalUuidAndPreferences() throws Exception {
		List<PrivacyPreferenceResponse> responses = List.of(
				new PrivacyPreferenceResponse("LOCATION", "Location", 0, true));
		when(service.bulkUpdatePreferences(userId, Map.of("LOCATION", 0))).thenReturn(responses);

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":0}}
								""")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].conceptCode").value("LOCATION"))
				.andExpect(jsonPath("$[0].effectiveSensitivity").value(0))
				.andExpect(jsonPath("$[0].explicit").value(true));

		verify(service).bulkUpdatePreferences(userId, Map.of("LOCATION", 0));
	}

	@Test
	void putRejectsOutOfRangeSensitivity() throws Exception {
		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":6}}
								""")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Validation failed"));

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":-1}}
								""")
						.principal(authentication))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void putRejectsMissingPreferences() throws Exception {
		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{}
								""")
						.principal(authentication))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void putRejectsMalformedBody() throws Exception {
		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":"high"}}
								""")
						.principal(authentication))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void unknownConceptMapsTo400Problem() throws Exception {
		when(service.bulkUpdatePreferences(userId, Map.of("NOPE", 3)))
				.thenThrow(new IllegalArgumentException("Concept not found: NOPE"));

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"NOPE":3}}
								""")
						.principal(authentication))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.detail").value("Concept not found: NOPE"));
	}

	@Test
	void clientSuppliedUserIdCannotOverridePrincipal() throws Exception {
		UUID foreignId = UUID.randomUUID();
		List<PrivacyPreferenceResponse> responses = List.of(
				new PrivacyPreferenceResponse("LOCATION", "Location", 3, false));
		when(service.getPreferences(userId)).thenReturn(responses);
		when(service.bulkUpdatePreferences(userId, Map.of("LOCATION", 3))).thenReturn(responses);

		mockMvc.perform(get("/api/v1/me/privacy-preferences")
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":3},"userId":"%s"}
								""".formatted(foreignId))
						.queryParam("userId", foreignId.toString())
						.header("X-User-Id", foreignId.toString())
						.principal(authentication))
				.andExpect(status().isOk());

		verify(service).getPreferences(userId);
		verify(service).bulkUpdatePreferences(userId, Map.of("LOCATION", 3));
	}

}
