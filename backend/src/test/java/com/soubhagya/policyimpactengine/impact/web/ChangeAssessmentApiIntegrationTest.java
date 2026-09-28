package com.soubhagya.policyimpactengine.impact.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.user.domain.User;

import tools.jackson.databind.JsonNode;

/**
 * Phase 14-B/3 — end-to-end change-assessment reads with security
 * filters enabled.
 *
 * <p>Proves an owner can read one change's assessment with breakdowns
 * filtered to that change, foreign and unknown changes behave as
 * not-found, an owned change without an assessment behaves as
 * not-found without creating one, and reads never mutate state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ChangeAssessmentApiIntegrationTest extends AssessmentRecommendationApiFixture {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Test
	void ownerReadsChangeAssessmentWithOnlyItsBreakdowns() throws Exception {
		String token = registerAndLogin("change-assessment@example.com");
		User user = userFor("change-assessment@example.com");
		Crafted crafted = craftAssessment(user, "LOCATION", "MODIFIED", 80, ImpactBand.HIGH);
		own(crafted.policy(), user);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 80);
		Crafted second = craftImpactOnly(crafted, "ADVERTISING", "ADDED", 42, ImpactBand.MEDIUM);
		addBreakdown(crafted.assessment(), second.impact(), 2, 42);
		UUID firstChangeId = crafted.impact().getMatch().getChange().getId();
		UUID secondChangeId = second.impact().getMatch().getChange().getId();

		MvcResult first = mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", firstChangeId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.change.id").value(firstChangeId.toString()))
				.andExpect(jsonPath("$.change.changeType").value("MODIFIED"))
				.andExpect(jsonPath("$.change.newVersionId")
						.value(crafted.v2().getId().toString()))
				.andExpect(jsonPath("$.assessment.id")
						.value(crafted.assessment().getId().toString()))
				.andExpect(jsonPath("$.assessment.versionNumber").value(2))
				.andExpect(jsonPath("$.assessment.previousVersionNumber").value(1))
				.andExpect(jsonPath("$.assessment.aggregateScore").value(80))
				.andExpect(jsonPath("$.assessment.aggregateBand").value("HIGH"))
				.andExpect(jsonPath("$.assessment.breakdowns.length()").value(1))
				.andExpect(jsonPath("$.assessment.breakdowns[0].conceptCode").value("LOCATION"))
				.andReturn();
		assertThat(changesOf(first).get(0).get("changeImpactId").asText())
				.isEqualTo(crafted.impact().getId().toString());

		// The second change of the same assessment carries only its
		// own breakdown: filtering is per requested change.
		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", secondChangeId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.change.id").value(secondChangeId.toString()))
				.andExpect(jsonPath("$.change.changeType").value("ADDED"))
				.andExpect(jsonPath("$.assessment.id")
						.value(crafted.assessment().getId().toString()))
				.andExpect(jsonPath("$.assessment.breakdowns.length()").value(1))
				.andExpect(jsonPath("$.assessment.breakdowns[0].conceptCode")
						.value("ADVERTISING"));
	}

	@Test
	void responseMatchesExistingDetailSemantics() throws Exception {
		String token = registerAndLogin("change-detail@example.com");
		User user = userFor("change-detail@example.com");
		Crafted crafted = craftAssessment(user, "COOKIES", "REMOVED", 70, ImpactBand.HIGH);
		own(crafted.policy(), user);
		addBreakdown(crafted.assessment(), crafted.impact(), 4, 70);
		Crafted second = craftImpactOnly(crafted, "LOCATION", "MODIFIED", 30, ImpactBand.MEDIUM);
		addBreakdown(crafted.assessment(), second.impact(), 1, 30);
		UUID changeId = crafted.impact().getMatch().getChange().getId();

		MvcResult changeView = mockMvc.perform(
						get("/api/v1/changes/{changeId}/assessment", changeId)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		MvcResult detailView = mockMvc.perform(
						get("/api/v1/me/impact-assessments/{assessmentId}",
								crafted.assessment().getId())
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode changeAssessment = bodyOf(changeView).get("assessment");
		JsonNode detail = bodyOf(detailView);
		assertThat(changeAssessment.get("id").asText()).isEqualTo(detail.get("id").asText());
		assertThat(changeAssessment.get("policyId").asText())
				.isEqualTo(detail.get("policyId").asText());
		assertThat(changeAssessment.get("versionNumber").asInt())
				.isEqualTo(detail.get("versionNumber").asInt());
		assertThat(changeAssessment.get("previousVersionNumber").asInt())
				.isEqualTo(detail.get("previousVersionNumber").asInt());
		assertThat(changeAssessment.get("aggregateScore").asInt())
				.isEqualTo(detail.get("aggregateScore").asInt());
		assertThat(changeAssessment.get("aggregateBand").asText())
				.isEqualTo(detail.get("aggregateBand").asText());
		assertThat(changeAssessment.get("personalizationRulesVersion").asInt())
				.isEqualTo(detail.get("personalizationRulesVersion").asInt());
		assertThat(changeAssessment.get("createdAt").asText())
				.isEqualTo(detail.get("createdAt").asText());

		// The single filtered breakdown is verbatim the matching row
		// of the full detail response.
		assertThat(changeAssessment.get("breakdowns").size()).isEqualTo(1);
		JsonNode filtered = changeAssessment.get("breakdowns").get(0);
		JsonNode match = null;
		for (JsonNode candidate : detail.get("breakdowns")) {
			if (candidate.get("changeImpactId").asText()
					.equals(filtered.get("changeImpactId").asText())) {
				match = candidate;
			}
		}
		assertThat(match).isNotNull();
		assertThat(filtered).isEqualTo(match);
	}

	@Test
	void foreignUserChangeReturns404() throws Exception {
		String token = registerAndLogin("change-owner@example.com");
		User user = userFor("change-owner@example.com");
		Crafted crafted = craftAssessment(user, "LOCATION", "MODIFIED", 80, ImpactBand.HIGH);
		own(crafted.policy(), user);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 80);
		UUID changeId = crafted.impact().getMatch().getChange().getId();
		String stranger = registerAndLogin("change-stranger@example.com");

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.header("Authorization", "Bearer " + stranger))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		// The owner still reads it: the stranger's 404 revealed
		// nothing and changed nothing.
		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
		assertThat(assessmentRepository.count()).isEqualTo(1);
	}

	@Test
	void unknownChangeReturns404() throws Exception {
		String token = registerAndLogin("change-unknown@example.com");

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", UUID.randomUUID())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void ownedChangeWithoutAssessmentReturns404AndCreatesNothing() throws Exception {
		String token = registerAndLogin("change-no-assessment@example.com");
		User user = userFor("change-no-assessment@example.com");
		UUID changeId = craftTransitionWithoutAssessment(user, "DATA_RETENTION");
		long assessmentsBefore = assessmentRepository.count();
		long breakdownsBefore = breakdownRepository.count();

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		assertThat(assessmentRepository.count()).isEqualTo(assessmentsBefore);
		assertThat(breakdownRepository.count()).isEqualTo(breakdownsBefore);
	}

	@Test
	void readsLeaveAllStateUnchangedAndRejectBadInput() throws Exception {
		String token = registerAndLogin("change-readonly@example.com");
		User user = userFor("change-readonly@example.com");
		Crafted crafted = craftAssessment(user, "LOCATION", "MODIFIED", 80, ImpactBand.HIGH);
		own(crafted.policy(), user);
		addBreakdown(crafted.assessment(), crafted.impact(), 3, 80);
		UUID changeId = crafted.impact().getMatch().getChange().getId();
		long assessmentsBefore = assessmentRepository.count();
		long breakdownsBefore = breakdownRepository.count();
		long recommendationsBefore = recommendationRepository.count();
		long changesBefore = changeRepository.count();

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		assertThat(assessmentRepository.count()).isEqualTo(assessmentsBefore);
		assertThat(breakdownRepository.count()).isEqualTo(breakdownsBefore);
		assertThat(recommendationRepository.count()).isEqualTo(recommendationsBefore);
		assertThat(changeRepository.count()).isEqualTo(changesBefore);

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		assertThat(assessmentRepository.count()).isEqualTo(assessmentsBefore);
		assertThat(breakdownRepository.count()).isEqualTo(breakdownsBefore);
		assertThat(recommendationRepository.count()).isEqualTo(recommendationsBefore);
		assertThat(changeRepository.count()).isEqualTo(changesBefore);
	}

	/**
	 * Assigns the crafted policy to its user so the owner-scoped
	 * change lookup resolves.
	 */
	private void own(Policy policy, User user) {
		policy.setOwner(user);
		policyRepository.saveAndFlush(policy);
	}

	/**
	 * Persists one owned transition with a single change, match, and
	 * system impact but deliberately no assessment row.
	 */
	private UUID craftTransitionWithoutAssessment(User user, String conceptCode) {
		PrivacyConcept concept = conceptRepository.findByCode(conceptCode).orElseThrow();
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		own(policy, user);
		String oldText = "old-" + UUID.randomUUID();
		String newText = "new-" + UUID.randomUUID();
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, oldText, hasher.hash(oldText)));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, newText, hasher.hash(newText)));
		PolicyChangeRecord change = changeRepository.saveAndFlush(new PolicyChangeRecord(
				v1, v2, PolicyChangeType.MODIFIED, oldText, newText, 0));
		ChangeConceptMatch match = matchRepository.saveAndFlush(new ChangeConceptMatch(
				change, concept, conceptCode.toLowerCase(), conceptCode + ":seed", "KEYWORD"));
		impactRepository.saveAndFlush(new ChangeImpact(match,
				conceptCode, "MODIFIED", 8, new BigDecimal("1.00"), new BigDecimal("8.00"),
				60, ImpactBand.HIGH, 1));
		return change.getId();
	}

	private List<JsonNode> changesOf(MvcResult result) throws Exception {
		JsonNode root = bodyOf(result);
		JsonNode changes = root.get("assessment").get("breakdowns");
		assertThat(changes.isArray()).isTrue();
		return objectMapper.convertValue(changes,
				objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
	}

	private JsonNode bodyOf(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
