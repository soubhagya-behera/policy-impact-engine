package com.soubhagya.policyimpactengine.impact.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import com.soubhagya.policyimpactengine.diff.DefaultPolicySimHash;
import com.soubhagya.policyimpactengine.diff.LineBasedPolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.DeterministicImpactScoringEngine;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.DeterministicConceptMatcher;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixture for the assessment/recommendation API integration
 * tests: Spring wiring, per-test database cleanup, register-login
 * helpers, crafted-domain builders, and ordering assertions.
 *
 * <p>Its single responsibility is fixture/data construction shared by
 * {@code ImpactAssessmentApiIntegrationTest} and
 * {@code RecommendationApiIntegrationTest}. Concrete test classes
 * carry the Spring Boot annotations and their own Testcontainers
 * declaration; this base carries no test methods and no production
 * behavior.
 */
abstract class AssessmentRecommendationApiFixture {

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected UserRepository userRepository;

	@Autowired
	protected UserPrivacyPreferenceRepository preferenceRepository;

	@Autowired
	protected PolicyRepository policyRepository;

	@Autowired
	protected PolicyVersionRepository versionRepository;

	@Autowired
	protected PolicyChangeRecordRepository changeRepository;

	@Autowired
	protected PrivacyConceptRepository conceptRepository;

	@Autowired
	protected ChangeConceptMatchRepository matchRepository;

	@Autowired
	protected ChangeImpactRepository impactRepository;

	@Autowired
	protected ImpactAssessmentRepository assessmentRepository;

	@Autowired
	protected ImpactAssessmentBreakdownRepository breakdownRepository;

	@Autowired
	protected RecommendationRepository recommendationRepository;

	@Autowired
	protected NotificationRepository notificationRepository;

	@Autowired
	protected PolicyVersionService versionService;

	@Autowired
	protected RecommendationService recommendationService;

	@Autowired
	protected PlatformTransactionManager transactionManager;

	@Autowired
	protected ObjectMapper objectMapper;

	protected final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	// Phase 11C: registration, login, and preference updates emit
	// audit rows referencing users, so audit rows go first or user
	// deletion violates the actor foreign key.
	@Autowired
	protected com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		notificationRepository.deleteAll();
		recommendationRepository.deleteAll();
		breakdownRepository.deleteAll();
		assessmentRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		preferenceRepository.deleteAll();
		userRepository.deleteAll();
	}

	protected PolicyObservationPersistenceService observationService() {
		return new PolicyObservationPersistenceService(versionService, versionRepository,
				new LineBasedPolicyDiffEngine(), changeRepository, new DefaultPolicySimHash(),
				conceptRepository, matchRepository, new DeterministicConceptMatcher(),
				impactRepository, new DeterministicImpactScoringEngine(), transactionManager);
	}

	/**
	 * Verifies newest-first ordering with the id as the final
	 * tie-break: every consecutive pair must be ordered by
	 * {@code createdAt} descending, or by {@code id} descending when
	 * the timestamps are equal.
	 */
	protected void assertSortedNewestFirst(JsonNode rows) {
		for (int i = 0; i + 1 < rows.size(); i++) {
			Instant current = Instant.parse(rows.get(i).get("createdAt").asText());
			Instant next = Instant.parse(rows.get(i + 1).get("createdAt").asText());
			if (current.equals(next)) {
				assertThat(rows.get(i).get("id").asText())
						.isGreaterThanOrEqualTo(rows.get(i + 1).get("id").asText());
			}
			else {
				assertThat(current).isAfter(next);
			}
		}
	}

	protected Set<String> fieldNames(JsonNode node) {
		return new HashSet<>(node.propertyNames());
	}

	protected String registerAndLogin(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	protected User userFor(String email) {
		return userRepository.findByEmail(email).orElseThrow();
	}

	/**
	 * Persists one controlled transition (v1 → v2) with exactly one
	 * change, match, system impact, and user assessment whose
	 * aggregate equals the item's normalized score — mirroring the
	 * existing recommendation-test fixture style. No breakdown or
	 * recommendation rows are created; callers add them explicitly.
	 */
	protected Crafted craftAssessment(User user, String conceptCode, String changeType,
			int normalized, ImpactBand band) {
		PrivacyConcept concept = conceptRepository.findByCode(conceptCode).orElseThrow();
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		String oldText = "old-" + UUID.randomUUID();
		String newText = "new-" + UUID.randomUUID() + " " + conceptCode.toLowerCase();
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, oldText, hasher.hash(oldText)));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, newText, hasher.hash(newText)));
		String changeOldText = "ADDED".equals(changeType) ? null : oldText;
		String changeNewText = "REMOVED".equals(changeType) ? null : newText;
		PolicyChangeRecord change = changeRepository.saveAndFlush(new PolicyChangeRecord(
				v1, v2, PolicyChangeType.valueOf(changeType), changeOldText, changeNewText, 0));
		ChangeConceptMatch match = matchRepository.saveAndFlush(new ChangeConceptMatch(
				change, concept, conceptCode.toLowerCase(), conceptCode + ":seed", "KEYWORD"));
		ChangeImpact impact = impactRepository.saveAndFlush(new ChangeImpact(match,
				conceptCode, changeType, 8, new BigDecimal("1.00"), new BigDecimal("8.00"),
				normalized, band, 1));
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(
				new ImpactAssessment(user, v2, v1, normalized, band, 1));
		return new Crafted(policy, v1, v2, assessment, impact);
	}

	/**
	 * Adds a second system impact (with its own change and match) to
	 * the crafted transition's versions, returning a holder for the
	 * new impact so callers can attach a second breakdown row.
	 */
	protected Crafted craftImpactOnly(Crafted crafted, String conceptCode, String changeType,
			int normalized, ImpactBand band) {
		PrivacyConcept concept = conceptRepository.findByCode(conceptCode).orElseThrow();
		String oldText = "old-" + UUID.randomUUID();
		String newText = "new-" + UUID.randomUUID() + " " + conceptCode.toLowerCase();
		String changeOldText = "ADDED".equals(changeType) ? null : oldText;
		String changeNewText = "REMOVED".equals(changeType) ? null : newText;
		PolicyChangeRecord change = changeRepository.saveAndFlush(new PolicyChangeRecord(
				crafted.v1(), crafted.v2(), PolicyChangeType.valueOf(changeType),
				changeOldText, changeNewText, 1));
		ChangeConceptMatch match = matchRepository.saveAndFlush(new ChangeConceptMatch(
				change, concept, conceptCode.toLowerCase(), conceptCode + ":seed", "KEYWORD"));
		ChangeImpact impact = impactRepository.saveAndFlush(new ChangeImpact(match,
				conceptCode, changeType, 7, new BigDecimal("1.00"), new BigDecimal("7.00"),
				normalized, band, 1));
		return new Crafted(crafted.policy(), crafted.v1(), crafted.v2(),
				crafted.assessment(), impact);
	}

	protected void addBreakdown(ImpactAssessment assessment, ChangeImpact impact,
			int effectiveSensitivity, int personalizedNormalized) {
		breakdownRepository.saveAndFlush(new ImpactAssessmentBreakdown(assessment, impact,
				effectiveSensitivity, personalizedNormalized,
				ImpactBand.fromNormalizedScore(personalizedNormalized), 1));
	}

	protected Recommendation craftRecommendation(ImpactAssessment assessment, String ruleId,
			int ruleOrder, RecommendationActionKind actionKind, String conceptCode,
			int personalizedNormalized, ImpactBand personalizedBand) {
		return recommendationRepository.saveAndFlush(new Recommendation(assessment, ruleId,
				ruleOrder, actionKind, conceptCode, personalizedNormalized, personalizedBand, 1));
	}

	protected record Crafted(Policy policy, PolicyVersion v1, PolicyVersion v2,
			ImpactAssessment assessment, ChangeImpact impact) {
	}

}
