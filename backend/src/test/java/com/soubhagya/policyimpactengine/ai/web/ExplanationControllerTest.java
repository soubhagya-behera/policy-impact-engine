package com.soubhagya.policyimpactengine.ai.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.soubhagya.policyimpactengine.ai.AiExplanationService;
import com.soubhagya.policyimpactengine.ai.FallbackReason;
import com.soubhagya.policyimpactengine.ai.web.dto.ExplanationResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;
import com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter;

/**
 * Phase 12 — web-layer tests for the explanation endpoint. The
 * application service is mocked; delegation, principal resolution,
 * response shape, and RFC 7807 problem responses are verified here.
 * Security filters are disabled: the filter chain itself is covered
 * with filters enabled elsewhere.
 */
@WebMvcTest(ExplanationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExplanationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AiExplanationService service;

	// Satisfies SecurityConfig wiring in this slice. Filters stay
	// disabled, so the mock never executes.
	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	// Phase 13-C: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private com.soubhagya.policyimpactengine.common.ratelimit.web.RateLimitFilter rateLimitFilter;

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
	void explainDelegatesAndReturnsResponseShape() throws Exception {
		UUID assessmentId = UUID.randomUUID();
		when(service.explain(userId, assessmentId)).thenReturn(new ExplanationResponse(
				assessmentId, "Plain explanation.", 80, "CRITICAL",
				List.of("REVIEW_SETTINGS"), "stub-model", false, null));

		mockMvc.perform(post("/api/v1/me/impact-assessments/" + assessmentId + "/explanation")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.assessmentId").value(assessmentId.toString()))
				.andExpect(jsonPath("$.explanation").value("Plain explanation."))
				.andExpect(jsonPath("$.aggregateScore").value(80))
				.andExpect(jsonPath("$.aggregateBand").value("CRITICAL"))
				.andExpect(jsonPath("$.actionKinds[0]").value("REVIEW_SETTINGS"))
				.andExpect(jsonPath("$.model").value("stub-model"))
				.andExpect(jsonPath("$.fallback").value(false))
				.andExpect(jsonPath("$.fallbackReason").value(nullValue()));

		verify(service).explain(userId, assessmentId);
	}

	@Test
	void fallbackResponseShape() throws Exception {
		UUID assessmentId = UUID.randomUUID();
		when(service.explain(userId, assessmentId)).thenReturn(new ExplanationResponse(
				assessmentId, "Fallback prose.", 0, "NONE", List.of(),
				"deterministic-fallback", true, FallbackReason.DISABLED));

		mockMvc.perform(post("/api/v1/me/impact-assessments/" + assessmentId + "/explanation")
						.principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fallback").value(true))
				.andExpect(jsonPath("$.fallbackReason").value("DISABLED"));

		verify(service).explain(userId, assessmentId);
	}

	@Test
	void foreignAssessmentReturnsNotFound() throws Exception {
		UUID assessmentId = UUID.randomUUID();
		when(service.explain(userId, assessmentId))
				.thenThrow(new NoSuchElementException("Assessment not found"));

		mockMvc.perform(post("/api/v1/me/impact-assessments/" + assessmentId + "/explanation")
						.principal(authentication))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void malformedAssessmentIdReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/v1/me/impact-assessments/not-a-uuid/explanation")
						.principal(authentication))
				.andExpect(status().isBadRequest());
	}

	@Test
	void missingPrincipalReturnsUnauthenticatedProblem() throws Exception {
		SecurityContextHolder.clearContext();

		mockMvc.perform(post("/api/v1/me/impact-assessments/" + UUID.randomUUID()
						+ "/explanation"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}
}
