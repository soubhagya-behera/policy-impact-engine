package com.soubhagya.policyimpactengine.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;

/**
 * Phase 2R — Testcontainers repository tests: append-only persistence,
 * partial-unique dedup, closure uniqueness, concept-code nullity check
 * constraint, user-scoped reads, immutability conventions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RecommendationRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private PolicyChangeRecordRepository changeRepository;
	@Autowired private PrivacyConceptRepository conceptRepository;
	@Autowired private ChangeConceptMatchRepository matchRepository;
	@Autowired private ChangeImpactRepository impactRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private ImpactAssessmentRepository assessmentRepository;
	@Autowired private RecommendationRepository recommendationRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void clean() {
		recommendationRepository.deleteAll();
		assessmentRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void persistenceRoundTripOrderedRead() {
		Fixture f = fixture();
		recommendationRepository.saveAndFlush(new Recommendation(
				f.assessment(), "REC-DELETION-RIGHTS-LOST", 1,
				RecommendationActionKind.EXERCISE_DELETION, "DELETION_RIGHTS",
				80, ImpactBand.CRITICAL, 1));
		recommendationRepository.saveAndFlush(new Recommendation(
				f.assessment(), "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, "DELETION_RIGHTS",
				80, ImpactBand.CRITICAL, 1));
		recommendationRepository.saveAndFlush(new Recommendation(
				f.assessment(), "REC-NONE-REQUIRED", 4,
				RecommendationActionKind.NONE_REQUIRED, null,
				10, ImpactBand.LOW, 1));

		List<Recommendation> rows = recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						f.assessment().getId());
		assertThat(rows).hasSize(3);
		assertThat(rows).extracting(Recommendation::getActionKind)
				.containsExactly(RecommendationActionKind.EXERCISE_DELETION,
						RecommendationActionKind.REVIEW_SETTINGS,
						RecommendationActionKind.NONE_REQUIRED);
		assertThat(rows).extracting(Recommendation::getRuleId)
				.containsExactly("REC-DELETION-RIGHTS-LOST", "REC-REVIEW-SETTINGS",
						"REC-NONE-REQUIRED");
		assertThat(rows).extracting(Recommendation::getRuleOrder).containsExactly(1, 3, 4);
		assertThat(rows.get(0).getConceptCode()).isEqualTo("DELETION_RIGHTS");
		assertThat(rows.get(2).getConceptCode()).isNull();
		assertThat(rows.get(0).getRecommendationRulesVersion()).isEqualTo(1);
		assertThat(rows.get(0).getAssessment().getId()).isEqualTo(f.assessment().getId());
		assertThat(rows.get(0).getCreatedAt()).isNotNull();
	}

	@Test
	void duplicateActionConceptRejected() {
		Fixture f = fixture();
		recommendationRepository.saveAndFlush(new Recommendation(
				f.assessment(), "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, "LOCATION",
				80, ImpactBand.CRITICAL, 1));
		DataIntegrityViolationException thrown = null;
		try {
			recommendationRepository.saveAndFlush(new Recommendation(
					f.assessment(), "REC-REVIEW-SETTINGS", 3,
					RecommendationActionKind.REVIEW_SETTINGS, "LOCATION",
					30, ImpactBand.MEDIUM, 1));
		}
		catch (DataIntegrityViolationException ex) {
			thrown = ex;
		}
		assertThat(thrown).isNotNull();
	}

	@Test
	void duplicateNoneRequiredRejected() {
		Fixture f = fixture();
		recommendationRepository.saveAndFlush(new Recommendation(
				f.assessment(), "REC-NONE-REQUIRED", 4,
				RecommendationActionKind.NONE_REQUIRED, null,
				0, ImpactBand.NONE, 1));
		DataIntegrityViolationException thrown = null;
		try {
			recommendationRepository.saveAndFlush(new Recommendation(
					f.assessment(), "REC-NONE-REQUIRED", 4,
					RecommendationActionKind.NONE_REQUIRED, null,
					20, ImpactBand.LOW, 1));
		}
		catch (DataIntegrityViolationException ex) {
			thrown = ex;
		}
		assertThat(thrown).isNotNull();
	}

	@Test
	void noneRequiredWithConceptCodeRejectedByCheckConstraint() {
		Fixture f = fixture();
		// The entity constructor also guards this; the database CHECK
		// (chk_recommendation_concept_code) is enforced below via raw SQL so
		// the constraint itself is verified, bypassing the entity guard.
		assertThatThrownBy(() -> new Recommendation(
				f.assessment(), "REC-NONE-REQUIRED", 4,
				RecommendationActionKind.NONE_REQUIRED, "COOKIES", 0, ImpactBand.NONE, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> insertRawRecommendation(f.assessment().getId(),
				"NONE_REQUIRED", "COOKIES"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void conceptLevelWithNullConceptCodeRejectedByCheckConstraint() {
		Fixture f = fixture();
		assertThatThrownBy(() -> new Recommendation(
				f.assessment(), "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, null, 30, ImpactBand.MEDIUM, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> insertRawRecommendation(f.assessment().getId(),
				"REVIEW_SETTINGS", null))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private int insertRawRecommendation(UUID assessmentId, String actionKind,
			String conceptCode) {
		return jdbcTemplate.update(
				"INSERT INTO recommendation (id, assessment_id, rule_id, rule_order, "
						+ "action_kind, concept_code, personalized_normalized, personalized_band, "
						+ "recommendation_rules_version, created_at) "
						+ "VALUES (?, ?, 'REC-TEST', 1, ?, ?, 0, 'NONE', 1, now())",
				UUID.randomUUID(), assessmentId, actionKind, conceptCode);
	}

	@Test
	void differentActionKindsSameConceptAllowed() {
		Fixture f = fixture();
		recommendationRepository.saveAndFlush(new Recommendation(
				f.assessment(), "REC-DELETION-RIGHTS-LOST", 1,
				RecommendationActionKind.EXERCISE_DELETION, "DELETION_RIGHTS",
				80, ImpactBand.CRITICAL, 1));
		recommendationRepository.saveAndFlush(new Recommendation(
				f.assessment(), "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, "DELETION_RIGHTS",
				80, ImpactBand.CRITICAL, 1));
		assertThat(recommendationRepository.count()).isEqualTo(2);
	}

	@Test
	void readsAreAssessmentScopedWithNoDuplicatedUserId() {
		Fixture f = fixture();
		recommendationRepository.saveAndFlush(new Recommendation(
				f.assessment(), "REC-NONE-REQUIRED", 4,
				RecommendationActionKind.NONE_REQUIRED, null,
				0, ImpactBand.NONE, 1));
		// Assessment-scoped read returns the row...
		assertThat(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						f.assessment().getId())).hasSize(1);
		// ...while an unrelated assessment sees nothing: isolation is
		// derived through the assessment relationship.
		User otherUser = userRepository.saveAndFlush(new User());
		ImpactAssessment otherAssessment = assessmentRepository.saveAndFlush(
				new ImpactAssessment(otherUser, f.v2(), f.v1(), 64, ImpactBand.HIGH, 1));
		assertThat(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						otherAssessment.getId())).isEmpty();
		// Regression: no duplicated user_id anywhere — neither on the
		// entity mapping nor on the table.
		assertThat(Arrays.stream(Recommendation.class.getDeclaredFields())
				.map(java.lang.reflect.Field::getName)).doesNotContain("user", "user_id");
		assertThat(jdbcTemplate.queryForList(
				"SELECT column_name FROM information_schema.columns "
						+ "WHERE table_name='recommendation'")
				.stream().map(column -> column.get("column_name")))
				.doesNotContain("user_id");
	}

	@Test
	void immutabilityConventions() {
		List<String> setters = Arrays.stream(Recommendation.class.getMethods())
				.map(Method::getName).filter(n -> n.startsWith("set")).toList();
		assertThat(setters).isEmpty();
		Arrays.stream(Recommendation.class.getDeclaredFields())
				.filter(f -> f.isAnnotationPresent(Column.class))
				.forEach(f -> assertThat(f.getAnnotation(Column.class).updatable())
						.as("Column %s", f.getName()).isFalse());
		Arrays.stream(Recommendation.class.getDeclaredFields())
				.filter(f -> f.isAnnotationPresent(JoinColumn.class))
				.forEach(f -> assertThat(f.getAnnotation(JoinColumn.class).updatable())
						.as("Join %s", f.getName()).isFalse());
	}

	private record Fixture(User user, Policy policy, PolicyVersion v1, PolicyVersion v2,
			ImpactAssessment assessment) {
	}

	private Fixture fixture() {
		PrivacyConcept concept = conceptRepository.findByCode("DELETION_RIGHTS").orElseThrow();
		Policy policy = policyRepository.saveAndFlush(
				new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		String oldText = "old-" + UUID.randomUUID();
		String newText = "delete personal data " + UUID.randomUUID();
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, oldText, hasher.hash(oldText)));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, newText, hasher.hash(newText)));
		PolicyChangeRecord change = changeRepository.saveAndFlush(
				new PolicyChangeRecord(v1, v2, PolicyChangeType.REMOVED, oldText, null, 0));
		ChangeConceptMatch match = matchRepository.saveAndFlush(
				new ChangeConceptMatch(change, concept, "delete", "DELETION_RIGHTS:delete", "KEYWORD"));
		impactRepository.saveAndFlush(new ChangeImpact(match, "DELETION_RIGHTS",
				"REMOVED", 8, new BigDecimal("0.80"), new BigDecimal("6.40"),
				64, ImpactBand.HIGH, 1));
		User user = userRepository.saveAndFlush(new User());
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(
				new ImpactAssessment(user, v2, v1, 64, ImpactBand.HIGH, 1));
		return new Fixture(user, policy, v1, v2, assessment);
	}
}
