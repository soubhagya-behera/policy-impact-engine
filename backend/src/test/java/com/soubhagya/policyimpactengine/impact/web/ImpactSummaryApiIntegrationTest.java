package com.soubhagya.policyimpactengine.impact.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;
import com.soubhagya.policyimpactengine.user.domain.User;

import tools.jackson.databind.JsonNode;

/**
 * Phase 14-B/4 — end-to-end impact-summary reads with security
 * filters enabled.
 *
 * <p>Proves the summary aggregates only the caller's persisted facts
 * (totals, per-band counts, maximum, actionable recommendations
 * without NONE_REQUIRED, newest latest), isolates users, returns the
 * zero/null empty state, never mutates state, and resolves every
 * value from database aggregates without bulk-loading entities.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ImpactSummaryApiIntegrationTest extends AssessmentRecommendationApiFixture {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void populatedSummaryMatchesPersistedFacts() throws Exception {
		String token = registerAndLogin("summary-populated@example.com");
		User user = userFor("summary-populated@example.com");
		Crafted first = craftAssessment(user, "LOCATION", "MODIFIED", 90, ImpactBand.CRITICAL);
		Crafted second = craftAssessment(user, "ADVERTISING", "ADDED", 70, ImpactBand.HIGH);
		Crafted third = craftAssessment(user, "COOKIES", "REMOVED", 60, ImpactBand.HIGH);
		craftRecommendation(first.assessment(), "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, "LOCATION", 90, ImpactBand.CRITICAL);
		craftRecommendation(first.assessment(), "REC-NONE-REQUIRED", 4,
				RecommendationActionKind.NONE_REQUIRED, null, 90, ImpactBand.CRITICAL);
		craftRecommendation(second.assessment(), "REC-SHARING-OPT-OUT", 2,
				RecommendationActionKind.OPT_OUT_SHARING, "ADVERTISING", 70, ImpactBand.HIGH);
		craftRecommendation(third.assessment(), "REC-NONE-REQUIRED", 4,
				RecommendationActionKind.NONE_REQUIRED, null, 60, ImpactBand.HIGH);

		MvcResult summary = mockMvc.perform(get("/api/v1/me/impact-summary")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(3))
				.andExpect(jsonPath("$.assessmentsByBand.length()").value(2))
				.andExpect(jsonPath("$.assessmentsByBand[0].band").value("CRITICAL"))
				.andExpect(jsonPath("$.assessmentsByBand[0].count").value(1))
				.andExpect(jsonPath("$.assessmentsByBand[1].band").value("HIGH"))
				.andExpect(jsonPath("$.assessmentsByBand[1].count").value(2))
				.andExpect(jsonPath("$.maxAggregateScore").value(90))
				.andExpect(jsonPath("$.actionableRecommendations").value(2))
				.andExpect(jsonPath("$.latest").isMap())
				.andReturn();

		// Latest matches the first row of the existing newest-first
		// assessment listing exactly.
		MvcResult listing = mockMvc.perform(get("/api/v1/me/impact-assessments")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode newest = objectMapper.readTree(
				listing.getResponse().getContentAsString()).get(0);
		JsonNode latest = objectMapper.readTree(
				summary.getResponse().getContentAsString()).get("latest");
		assertThat(latest.get("id").asText()).isEqualTo(newest.get("id").asText());
		assertThat(latest).isEqualTo(newest);
	}

	@Test
	void secondUserDataIsNeverIncluded() throws Exception {
		String token = registerAndLogin("summary-owner@example.com");
		User user = userFor("summary-owner@example.com");
		Crafted owned = craftAssessment(user, "LOCATION", "MODIFIED", 70, ImpactBand.HIGH);
		craftRecommendation(owned.assessment(), "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, "LOCATION", 70, ImpactBand.HIGH);

		String strangerToken = registerAndLogin("summary-stranger@example.com");
		User stranger = userFor("summary-stranger@example.com");
		Crafted foreign = craftAssessment(stranger, "COOKIES", "REMOVED", 95, ImpactBand.CRITICAL);
		craftRecommendation(foreign.assessment(), "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, "COOKIES", 95, ImpactBand.CRITICAL);
		craftRecommendation(foreign.assessment(), "REC-SHARING-OPT-OUT", 2,
				RecommendationActionKind.OPT_OUT_SHARING, "COOKIES", 95, ImpactBand.CRITICAL);

		mockMvc.perform(get("/api/v1/me/impact-summary")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(1))
				.andExpect(jsonPath("$.assessmentsByBand.length()").value(1))
				.andExpect(jsonPath("$.assessmentsByBand[0].band").value("HIGH"))
				.andExpect(jsonPath("$.maxAggregateScore").value(70))
				.andExpect(jsonPath("$.actionableRecommendations").value(1))
				.andExpect(jsonPath("$.latest.id")
						.value(owned.assessment().getId().toString()));

		mockMvc.perform(get("/api/v1/me/impact-summary")
						.header("Authorization", "Bearer " + strangerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(1))
				.andExpect(jsonPath("$.maxAggregateScore").value(95))
				.andExpect(jsonPath("$.actionableRecommendations").value(2));
	}

	@Test
	void emptyUserReturnsZeroNullState() throws Exception {
		String token = registerAndLogin("summary-empty@example.com");

		mockMvc.perform(get("/api/v1/me/impact-summary")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(0))
				.andExpect(jsonPath("$.assessmentsByBand").isArray())
				.andExpect(jsonPath("$.assessmentsByBand.length()").value(0))
				.andExpect(jsonPath("$.maxAggregateScore").value(0))
				.andExpect(jsonPath("$.actionableRecommendations").value(0))
				.andExpect(jsonPath("$.latest").isEmpty());
	}

	@Test
	void readsLeaveAllStateUnchangedAndNeedNoParameters() throws Exception {
		String token = registerAndLogin("summary-readonly@example.com");
		User user = userFor("summary-readonly@example.com");
		Crafted crafted = craftAssessment(user, "LOCATION", "MODIFIED", 80, ImpactBand.HIGH);
		craftRecommendation(crafted.assessment(), "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, "LOCATION", 80, ImpactBand.HIGH);
		long assessmentsBefore = assessmentRepository.count();
		long breakdownsBefore = breakdownRepository.count();
		long recommendationsBefore = recommendationRepository.count();
		long changesBefore = changeRepository.count();

		mockMvc.perform(get("/api/v1/me/impact-summary")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(1));

		// Pagination-style parameters are neither required nor
		// interpreted: the same single object is returned.
		MvcResult plain = mockMvc.perform(get("/api/v1/me/impact-summary")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		MvcResult paged = mockMvc.perform(get("/api/v1/me/impact-summary")
						.queryParam("page", "0")
						.queryParam("size", "5")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(objectMapper.readTree(paged.getResponse().getContentAsString()))
				.isEqualTo(objectMapper.readTree(plain.getResponse().getContentAsString()));

		mockMvc.perform(get("/api/v1/me/impact-summary"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		assertThat(assessmentRepository.count()).isEqualTo(assessmentsBefore);
		assertThat(breakdownRepository.count()).isEqualTo(breakdownsBefore);
		assertThat(recommendationRepository.count()).isEqualTo(recommendationsBefore);
		assertThat(changeRepository.count()).isEqualTo(changesBefore);
	}

	@Test
	void summaryResolvesAggregatesWithoutBulkEntityLoading() throws Exception {
		String token = registerAndLogin("summary-aggregates@example.com");
		User user = userFor("summary-aggregates@example.com");
		for (int i = 0; i < 3; i++) {
			Crafted crafted = craftAssessment(user, "LOCATION", "MODIFIED",
					60 + i, ImpactBand.fromNormalizedScore(60 + i));
			craftRecommendation(crafted.assessment(), "REC-REVIEW-SETTINGS-" + i, 3,
					RecommendationActionKind.REVIEW_SETTINGS, "LOCATION",
					60 + i, ImpactBand.fromNormalizedScore(60 + i));
		}

		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		stats.clear();
		mockMvc.perform(get("/api/v1/me/impact-summary")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(3))
				.andExpect(jsonPath("$.actionableRecommendations").value(3));

		// Three assessments and three recommendations exist, yet only
		// the single latest assessment row is materialized and no
		// recommendation row is loaded: totals come from COUNT/GROUP
		// BY/MAX aggregates. (Direct query loads surface as load
		// counts; association-triggered loads as fetch counts.)
		assertThat(stats.getEntityStatistics(ImpactAssessment.class.getName()).getLoadCount())
				.as("assessment entity loads").isEqualTo(1);
		assertThat(stats.getEntityStatistics(Recommendation.class.getName()).getLoadCount())
				.as("recommendation entity loads").isZero();
		assertThat(stats.getEntityStatistics(Recommendation.class.getName()).getFetchCount())
				.as("recommendation entity fetches").isZero();
	}
}
