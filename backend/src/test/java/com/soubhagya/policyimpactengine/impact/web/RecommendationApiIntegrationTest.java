package com.soubhagya.policyimpactengine.impact.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;
import com.soubhagya.policyimpactengine.user.domain.User;

import tools.jackson.databind.JsonNode;

/**
 * End-to-end tests for the authenticated recommendation read API
 * with security filters enabled.
 *
 * <p>Exercises the real flow (register → login → Bearer) against
 * Testcontainers PostgreSQL and proves authentication, user
 * isolation (foreign rows read as 404), deterministic ordering,
 * exact response shapes with assessment context, empty states,
 * identity-injection resistance, and visibility of a real
 * policy-change → assessment → recommendation pipeline run. Shared
 * wiring and fixtures live in
 * {@link AssessmentRecommendationApiFixture}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecommendationApiIntegrationTest extends AssessmentRecommendationApiFixture {

	private static final Set<String> RECOMMENDATION_SUMMARY_FIELDS = Set.of(
			"id", "assessmentId", "ruleId", "ruleOrder", "actionKind",
			"conceptCode", "personalizedNormalized", "personalizedBand",
			"recommendationRulesVersion", "createdAt");

	private static final Set<String> RECOMMENDATION_DETAIL_FIELDS = Set.of(
			"id", "assessmentId", "policyId", "versionNumber", "ruleId",
			"ruleOrder", "actionKind", "conceptCode", "personalizedNormalized",
			"personalizedBand", "recommendationRulesVersion", "createdAt");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Test
	void unauthenticatedRecommendationRequestsReturn401() throws Exception {
		mockMvc.perform(get("/api/v1/me/recommendations"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/me/recommendations/{id}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void authenticatedUserWithNoRecommendationsReceivesEmptyList() throws Exception {
		String token = registerAndLogin("empty-state@example.com");

		mockMvc.perform(get("/api/v1/me/recommendations")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void userSeesOnlyOwnRecommendations() throws Exception {
		String tokenA = registerAndLogin("rec-a@example.com");
		registerAndLogin("rec-b@example.com");
		User userA = userFor("rec-a@example.com");
		User userB = userFor("rec-b@example.com");
		Crafted own = craftAssessment(userA, "THIRD_PARTY_SHARING", "MODIFIED",
				100, ImpactBand.CRITICAL);
		Recommendation ownRow = craftRecommendation(own.assessment(),
				"REC-SHARING-OPT-OUT", 2, RecommendationActionKind.OPT_OUT_SHARING,
				"THIRD_PARTY_SHARING", 100, ImpactBand.CRITICAL);
		Crafted foreign = craftAssessment(userB, "LOCATION", "MODIFIED",
				80, ImpactBand.CRITICAL);
		craftRecommendation(foreign.assessment(),
				"REC-REVIEW-SETTINGS", 3, RecommendationActionKind.REVIEW_SETTINGS,
				"LOCATION", 80, ImpactBand.CRITICAL);

		MvcResult result = mockMvc.perform(get("/api/v1/me/recommendations")
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(ownRow.getId().toString()))
				.andExpect(jsonPath("$[0].assessmentId")
						.value(own.assessment().getId().toString()))
				.andReturn();

		assertThat(fieldNames(objectMapper.readTree(
				result.getResponse().getContentAsString()).get(0)))
				.isEqualTo(RECOMMENDATION_SUMMARY_FIELDS);
	}

	@Test
	void crossUserAndUnknownRecommendationDetailReturn404() throws Exception {
		String tokenA = registerAndLogin("rec-cross-a@example.com");
		registerAndLogin("rec-cross-b@example.com");
		User userB = userFor("rec-cross-b@example.com");
		Crafted foreign = craftAssessment(userB, "LOCATION", "MODIFIED",
				80, ImpactBand.CRITICAL);
		Recommendation foreignRow = craftRecommendation(foreign.assessment(),
				"REC-REVIEW-SETTINGS", 3, RecommendationActionKind.REVIEW_SETTINGS,
				"LOCATION", 80, ImpactBand.CRITICAL);

		mockMvc.perform(get("/api/v1/me/recommendations/{id}", foreignRow.getId())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"));

		mockMvc.perform(get("/api/v1/me/recommendations/{id}", UUID.randomUUID())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void recommendationListIsDeterministicallyOrderedWithoutDuplicates() throws Exception {
		String token = registerAndLogin("rec-order@example.com");
		User user = userFor("rec-order@example.com");
		Crafted crafted = craftAssessment(user, "THIRD_PARTY_SHARING", "MODIFIED",
				100, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 100);
		craftRecommendation(crafted.assessment(),
				"REC-SHARING-OPT-OUT", 2, RecommendationActionKind.OPT_OUT_SHARING,
				"THIRD_PARTY_SHARING", 100, ImpactBand.CRITICAL);
		craftRecommendation(crafted.assessment(),
				"REC-REVIEW-SETTINGS", 3, RecommendationActionKind.REVIEW_SETTINGS,
				"THIRD_PARTY_SHARING", 100, ImpactBand.CRITICAL);

		MvcResult result = mockMvc.perform(get("/api/v1/me/recommendations")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andReturn();

		JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
		assertSortedNewestFirst(rows);
		List<String> ids = new ArrayList<>();
		for (JsonNode row : rows) {
			assertThat(fieldNames(row)).isEqualTo(RECOMMENDATION_SUMMARY_FIELDS);
			ids.add(row.get("id").asText());
		}
		assertThat(ids).doesNotHaveDuplicates();
	}

	@Test
	void recommendationDetailExposesAssessmentContext() throws Exception {
		String token = registerAndLogin("rec-detail@example.com");
		User user = userFor("rec-detail@example.com");
		Crafted crafted = craftAssessment(user, "THIRD_PARTY_SHARING", "MODIFIED",
				100, ImpactBand.CRITICAL);
		Recommendation row = craftRecommendation(crafted.assessment(),
				"REC-SHARING-OPT-OUT", 2, RecommendationActionKind.OPT_OUT_SHARING,
				"THIRD_PARTY_SHARING", 100, ImpactBand.CRITICAL);

		MvcResult result = mockMvc.perform(
						get("/api/v1/me/recommendations/{id}", row.getId())
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(row.getId().toString()))
				.andExpect(jsonPath("$.assessmentId")
						.value(crafted.assessment().getId().toString()))
				.andExpect(jsonPath("$.policyId").value(crafted.policy().getId().toString()))
				.andExpect(jsonPath("$.versionNumber").value(2))
				.andExpect(jsonPath("$.ruleId").value("REC-SHARING-OPT-OUT"))
				.andExpect(jsonPath("$.ruleOrder").value(2))
				.andExpect(jsonPath("$.actionKind").value("OPT_OUT_SHARING"))
				.andExpect(jsonPath("$.conceptCode").value("THIRD_PARTY_SHARING"))
				.andExpect(jsonPath("$.personalizedNormalized").value(100))
				.andExpect(jsonPath("$.personalizedBand").value("CRITICAL"))
				.andExpect(jsonPath("$.recommendationRulesVersion").value(1))
				.andReturn();

		assertThat(fieldNames(objectMapper.readTree(
				result.getResponse().getContentAsString())))
				.isEqualTo(RECOMMENDATION_DETAIL_FIELDS);
	}

	@Test
	void recommendationIdentityInjectionCannotOverridePrincipal() throws Exception {
		String tokenA = registerAndLogin("inject-a@example.com");
		registerAndLogin("inject-b@example.com");
		User userA = userFor("inject-a@example.com");
		User userB = userFor("inject-b@example.com");
		Crafted own = craftAssessment(userA, "LOCATION", "MODIFIED",
				80, ImpactBand.CRITICAL);
		Recommendation ownRow = craftRecommendation(own.assessment(),
				"REC-REVIEW-SETTINGS", 3, RecommendationActionKind.REVIEW_SETTINGS,
				"LOCATION", 80, ImpactBand.CRITICAL);
		Crafted foreign = craftAssessment(userB, "COOKIES", "ADDED",
				16, ImpactBand.LOW);
		craftRecommendation(foreign.assessment(),
				"REC-NONE-REQUIRED", 4, RecommendationActionKind.NONE_REQUIRED,
				null, 16, ImpactBand.LOW);

		String spoof = "?userId=" + userB.getId();
		mockMvc.perform(get("/api/v1/me/recommendations" + spoof)
						.header("X-User-Id", userB.getId().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(ownRow.getId().toString()));

		mockMvc.perform(get("/api/v1/me/recommendations/{id}" + spoof, ownRow.getId())
						.header("X-User-Id", userB.getId().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(ownRow.getId().toString()));
	}

	@Test
	void malformedRecommendationUuidReturns400() throws Exception {
		String token = registerAndLogin("malformed-ids@example.com");

		mockMvc.perform(get("/api/v1/me/recommendations/{id}", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"));
	}

	@Test
	void realPipelineFlowIsVisibleThroughRest() throws Exception {
		String token = registerAndLogin("pipeline-flow@example.com");
		User user = userFor("pipeline-flow@example.com");

		// Real generation path, unchanged: observation → assessment →
		// recommendation through the existing services.
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"Pipeline Policy", "https://example.com/pipeline"));
		PolicyObservationPersistenceService persistence = observationService();
		String first = "We respect your privacy.\n";
		persistence.store(policy.getId(), first, hasher.hash(first));
		String second = "We share your location data with third parties and advertising partners.\n";
		persistence.store(policy.getId(), second, hasher.hash(second));
		PolicyVersion v2 = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();

		List<Recommendation> recommendations = recommendationService
				.getOrCreateRecommendations(user.getId(), v2.getId());
		assertThat(recommendations).isNotEmpty();

		ImpactAssessment assessment = assessmentRepository
				.findByUser_IdAndNewVersion_Id(user.getId(), v2.getId()).orElseThrow();

		// The assessment list exposes the generated assessment.
		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(assessment.getId().toString()))
				.andExpect(jsonPath("$[0].policyId").value(policy.getId().toString()))
				.andExpect(jsonPath("$[0].versionNumber").value(2))
				.andExpect(jsonPath("$[0].previousVersionNumber").value(1));

		// The detail exposes the generated breakdowns.
		mockMvc.perform(get("/api/v1/me/impact-assessments/{id}", assessment.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(assessment.getId().toString()))
				.andExpect(jsonPath("$.breakdowns").isArray())
				.andExpect(jsonPath("$.breakdowns.length()")
						.value(breakdownRepository
								.findByAssessment_IdOrderByPersonalizedNormalizedDescConceptCodeAsc(
										assessment.getId()).size()));

		// The recommendation list exposes every generated row, each
		// pointing at the same assessment the notification feed would
		// reference.
		MvcResult recs = mockMvc.perform(get("/api/v1/me/recommendations")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(recommendations.size()))
				.andReturn();
		JsonNode rows = objectMapper.readTree(recs.getResponse().getContentAsString());
		for (JsonNode row : rows) {
			assertThat(row.get("assessmentId").asText())
					.isEqualTo(assessment.getId().toString());
		}

		// And the detail of one generated row resolves with context.
		mockMvc.perform(get("/api/v1/me/recommendations/{id}", recommendations.get(0).getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assessmentId").value(assessment.getId().toString()))
				.andExpect(jsonPath("$.policyId").value(policy.getId().toString()))
				.andExpect(jsonPath("$.versionNumber").value(2));
	}

}
