package com.soubhagya.policyimpactengine.impact.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
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
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.user.domain.User;

import tools.jackson.databind.JsonNode;

/**
 * End-to-end tests for the authenticated impact-assessment read API
 * with security filters enabled.
 *
 * <p>Exercises the real flow (register → login → Bearer) against
 * Testcontainers PostgreSQL and proves authentication, user
 * isolation (foreign rows read as 404), deterministic ordering,
 * exact summary/detail/breakdown shapes with no score
 * recomputation, and identity-injection resistance. Shared wiring
 * and fixtures live in {@link AssessmentRecommendationApiFixture}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ImpactAssessmentApiIntegrationTest extends AssessmentRecommendationApiFixture {

	private static final Set<String> ASSESSMENT_SUMMARY_FIELDS = Set.of(
			"id", "policyId", "versionNumber", "previousVersionNumber",
			"aggregateScore", "aggregateBand", "personalizationRulesVersion",
			"createdAt");

	private static final Set<String> BREAKDOWN_FIELDS = Set.of(
			"changeImpactId", "conceptCode", "changeType", "systemNormalized",
			"systemBand", "systemRulesVersion", "effectiveSensitivity",
			"personalizedNormalized", "personalizedBand",
			"personalizationRulesVersion");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Test
	void unauthenticatedAssessmentRequestsReturn401() throws Exception {
		mockMvc.perform(get("/api/v1/me/impact-assessments"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/me/impact-assessments/{id}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void authenticatedUserWithNoAssessmentsReceivesEmptyList() throws Exception {
		String token = registerAndLogin("empty-state@example.com");

		mockMvc.perform(get("/api/v1/me/impact-assessments")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void userSeesOnlyOwnAssessments() throws Exception {
		String tokenA = registerAndLogin("assess-a@example.com");
		registerAndLogin("assess-b@example.com");
		User userA = userFor("assess-a@example.com");
		User userB = userFor("assess-b@example.com");
		Crafted own = craftAssessment(userA, "LOCATION", "MODIFIED", 80, ImpactBand.CRITICAL);
		craftAssessment(userB, "COOKIES", "ADDED", 16, ImpactBand.LOW);

		MvcResult result = mockMvc.perform(get("/api/v1/me/impact-assessments")
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(own.assessment().getId().toString()))
				.andReturn();

		assertThat(fieldNames(objectMapper.readTree(
				result.getResponse().getContentAsString()).get(0)))
				.isEqualTo(ASSESSMENT_SUMMARY_FIELDS);
	}

	@Test
	void crossUserAndUnknownAssessmentDetailReturn404() throws Exception {
		String tokenA = registerAndLogin("assess-cross-a@example.com");
		registerAndLogin("assess-cross-b@example.com");
		User userB = userFor("assess-cross-b@example.com");
		Crafted foreign = craftAssessment(userB, "LOCATION", "MODIFIED", 80, ImpactBand.CRITICAL);

		mockMvc.perform(get("/api/v1/me/impact-assessments/{id}", foreign.assessment().getId())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"));

		mockMvc.perform(get("/api/v1/me/impact-assessments/{id}", UUID.randomUUID())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void assessmentListIsDeterministicallyOrderedWithSummaryFieldsOnly() throws Exception {
		String token = registerAndLogin("assess-order@example.com");
		User user = userFor("assess-order@example.com");
		craftAssessment(user, "LOCATION", "MODIFIED", 80, ImpactBand.CRITICAL);
		craftAssessment(user, "COOKIES", "ADDED", 16, ImpactBand.LOW);

		MvcResult result = mockMvc.perform(get("/api/v1/me/impact-assessments")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andReturn();

		JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
		assertSortedNewestFirst(rows);
		for (JsonNode row : rows) {
			assertThat(fieldNames(row)).isEqualTo(ASSESSMENT_SUMMARY_FIELDS);
			assertThat(row.get("breakdowns")).isNull();
		}
		// No duplicate rows: distinct assessment ids.
		assertThat(List.of(rows.get(0).get("id").asText(), rows.get(1).get("id").asText()))
				.doesNotHaveDuplicates();
	}

	@Test
	void assessmentDetailExposesPersistedFactsAndOrderedBreakdowns() throws Exception {
		String token = registerAndLogin("assess-detail@example.com");
		User user = userFor("assess-detail@example.com");
		Crafted crafted = craftAssessment(user, "THIRD_PARTY_SHARING", "MODIFIED",
				100, ImpactBand.CRITICAL);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 100);
		// Second breakdown with a lower personalized score pins the
		// ranking order (score desc, then concept code).
		Crafted second = craftImpactOnly(crafted, "ADVERTISING", "ADDED",
				70, ImpactBand.HIGH);
		addBreakdown(crafted.assessment(), second.impact(), 3, 70);

		MvcResult result = mockMvc.perform(
						get("/api/v1/me/impact-assessments/{id}", crafted.assessment().getId())
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(crafted.assessment().getId().toString()))
				.andExpect(jsonPath("$.policyId").value(crafted.policy().getId().toString()))
				.andExpect(jsonPath("$.versionNumber").value(2))
				.andExpect(jsonPath("$.previousVersionNumber").value(1))
				.andExpect(jsonPath("$.aggregateScore")
						.value(crafted.assessment().getAggregateScore()))
				.andExpect(jsonPath("$.aggregateBand")
						.value(crafted.assessment().getAggregateBand().name()))
				.andExpect(jsonPath("$.personalizationRulesVersion").value(1))
				.andExpect(jsonPath("$.breakdowns.length()").value(2))
				.andReturn();

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		Set<String> expectedDetailFields = new HashSet<>(ASSESSMENT_SUMMARY_FIELDS);
		expectedDetailFields.add("breakdowns");
		assertThat(new HashSet<>(body.propertyNames())).isEqualTo(expectedDetailFields);

		// Breakdowns arrive in engine ranking order and carry the
		// exact persisted values — the REST layer recomputes nothing.
		JsonNode first = body.get("breakdowns").get(0);
		assertThat(fieldNames(first)).isEqualTo(BREAKDOWN_FIELDS);
		assertThat(first.get("changeImpactId").asText())
				.isEqualTo(crafted.impact().getId().toString());
		assertThat(first.get("conceptCode").asText()).isEqualTo("THIRD_PARTY_SHARING");
		assertThat(first.get("changeType").asText()).isEqualTo("MODIFIED");
		assertThat(first.get("systemNormalized").asInt())
				.isEqualTo(crafted.impact().getNormalizedScore());
		assertThat(first.get("systemBand").asText())
				.isEqualTo(crafted.impact().getImpactBand().name());
		assertThat(first.get("systemRulesVersion").asInt())
				.isEqualTo(crafted.impact().getRulesVersion());
		assertThat(first.get("effectiveSensitivity").asInt()).isEqualTo(3);
		assertThat(first.get("personalizedNormalized").asInt()).isEqualTo(100);
		assertThat(first.get("personalizedBand").asText())
				.isEqualTo(ImpactBand.CRITICAL.name());
		assertThat(first.get("personalizationRulesVersion").asInt()).isEqualTo(1);

		JsonNode lower = body.get("breakdowns").get(1);
		assertThat(lower.get("conceptCode").asText()).isEqualTo("ADVERTISING");
		assertThat(lower.get("personalizedNormalized").asInt()).isEqualTo(70);

		// Direct repository comparison: every exposed number equals the
		// persisted row, proving no second scoring algorithm runs here.
		List<ImpactAssessmentBreakdown> persisted = breakdownRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescConceptCodeAsc(
						crafted.assessment().getId());
		assertThat(body.get("breakdowns").size()).isEqualTo(persisted.size());
		for (int i = 0; i < persisted.size(); i++) {
			JsonNode row = body.get("breakdowns").get(i);
			ImpactAssessmentBreakdown persistedRow = persisted.get(i);
			assertThat(row.get("changeImpactId").asText())
					.isEqualTo(persistedRow.getChangeImpact().getId().toString());
			assertThat(row.get("conceptCode").asText())
					.isEqualTo(persistedRow.getConceptCode());
			assertThat(row.get("systemNormalized").asInt())
					.isEqualTo(persistedRow.getSystemNormalized());
			assertThat(row.get("effectiveSensitivity").asInt())
					.isEqualTo(persistedRow.getEffectiveSensitivity());
			assertThat(row.get("personalizedNormalized").asInt())
					.isEqualTo(persistedRow.getPersonalizedNormalized());
		}
	}

	@Test
	void assessmentDetailWithNoBreakdownsReturnsEmptyArray() throws Exception {
		String token = registerAndLogin("assess-empty@example.com");
		User user = userFor("assess-empty@example.com");
		// An empty-impact transition persists 0/NONE with zero rows.
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		PolicyVersion v1 = versionRepository.saveAndFlush(new PolicyVersion(
				policy, 1, "old-" + UUID.randomUUID(), hasher.hash("old-" + UUID.randomUUID())));
		PolicyVersion v2 = versionRepository.saveAndFlush(new PolicyVersion(
				policy, 2, "new-" + UUID.randomUUID(), hasher.hash("new-" + UUID.randomUUID())));
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(
				new ImpactAssessment(user, v2, v1, 0, ImpactBand.NONE, 1));

		mockMvc.perform(get("/api/v1/me/impact-assessments/{id}", assessment.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(assessment.getId().toString()))
				.andExpect(jsonPath("$.aggregateScore").value(0))
				.andExpect(jsonPath("$.aggregateBand").value("NONE"))
				.andExpect(jsonPath("$.breakdowns").isArray())
				.andExpect(jsonPath("$.breakdowns.length()").value(0));
	}

	@Test
	void assessmentIdentityInjectionCannotOverridePrincipal() throws Exception {
		String tokenA = registerAndLogin("inject-a@example.com");
		registerAndLogin("inject-b@example.com");
		User userA = userFor("inject-a@example.com");
		User userB = userFor("inject-b@example.com");
		Crafted own = craftAssessment(userA, "LOCATION", "MODIFIED",
				80, ImpactBand.CRITICAL);
		Crafted foreign = craftAssessment(userB, "COOKIES", "ADDED",
				16, ImpactBand.LOW);

		String spoof = "?userId=" + userB.getId();
		mockMvc.perform(get("/api/v1/me/impact-assessments" + spoof)
						.header("X-User-Id", userB.getId().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(own.assessment().getId().toString()));

		mockMvc.perform(get("/api/v1/me/impact-assessments/{id}" + spoof, own.assessment().getId())
						.header("X-User-Id", userB.getId().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(own.assessment().getId().toString()));

		// The spoofed identity also cannot pull the foreign rows: the
		// query/header identity is ignored, so B's ids stay 404 for A.
		mockMvc.perform(get("/api/v1/me/impact-assessments/{id}", foreign.assessment().getId())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound());
	}

	@Test
	void malformedAssessmentUuidReturns400() throws Exception {
		String token = registerAndLogin("malformed-ids@example.com");

		mockMvc.perform(get("/api/v1/me/impact-assessments/{id}", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"));
	}

}
