package com.soubhagya.policyimpactengine.impact;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
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
 * Phase 2Q — Testcontainers repository tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ImpactAssessmentRepositoryTest {

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
	@Autowired private com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository preferenceRepository;
	@Autowired private ImpactAssessmentRepository assessmentRepository;
	@Autowired private ImpactAssessmentBreakdownRepository breakdownRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void clean() {
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

	@Test
	void v7MigrationApplies() {
		Integer v = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version='7' AND success=true", Integer.class);
		assertThat(v).isEqualTo(1);
	}

	@Test
	void assessmentPersistsWithFks() {
		Fixture f = fixture();
		ImpactAssessment saved = assessmentRepository.saveAndFlush(new ImpactAssessment(
				f.user(), f.v2(), f.v1(), 80, ImpactBand.CRITICAL, 1));
		ImpactAssessment found = assessmentRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getUser().getId()).isEqualTo(f.user().getId());
		assertThat(found.getNewVersion().getId()).isEqualTo(f.v2().getId());
		assertThat(found.getPreviousVersion().getId()).isEqualTo(f.v1().getId());
		assertThat(found.getAggregateScore()).isEqualTo(80);
		assertThat(found.getCreatedAt()).isNotNull();
	}

	@Test
	void breakdownPersistsWithSnapshots() {
		Fixture f = fixture();
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(new ImpactAssessment(
				f.user(), f.v2(), f.v1(), 80, ImpactBand.CRITICAL, 1));
		ImpactAssessmentBreakdown saved = breakdownRepository.saveAndFlush(
				new ImpactAssessmentBreakdown(assessment, f.impact(), 5, 100, ImpactBand.CRITICAL, 1));
		ImpactAssessmentBreakdown found = breakdownRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getConceptCode()).isEqualTo(f.impact().getConceptCode());
		assertThat(found.getSystemNormalized()).isEqualTo(f.impact().getNormalizedScore());
		assertThat(found.getEffectiveSensitivity()).isEqualTo(5);
		assertThat(found.getPersonalizedNormalized()).isEqualTo(100);
		List<ImpactAssessmentBreakdown> ordered = breakdownRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescConceptCodeAsc(assessment.getId());
		assertThat(ordered).hasSize(1);
	}

	@Test
	void uniqueUserNewVersionEnforced() {
		Fixture f = fixture();
		assessmentRepository.saveAndFlush(new ImpactAssessment(
				f.user(), f.v2(), f.v1(), 10, ImpactBand.LOW, 1));
		org.springframework.dao.DataIntegrityViolationException thrown = null;
		try {
			assessmentRepository.saveAndFlush(new ImpactAssessment(
					f.user(), f.v2(), f.v1(), 20, ImpactBand.LOW, 1));
		} catch (org.springframework.dao.DataIntegrityViolationException ex) {
			thrown = ex;
		}
		assertThat(thrown).isNotNull();
	}

	@Test
	void uniqueAssessmentImpactEnforced() {
		Fixture f = fixture();
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(new ImpactAssessment(
				f.user(), f.v2(), f.v1(), 80, ImpactBand.CRITICAL, 1));
		breakdownRepository.saveAndFlush(new ImpactAssessmentBreakdown(
				assessment, f.impact(), 3, 80, ImpactBand.CRITICAL, 1));
		org.springframework.dao.DataIntegrityViolationException thrown = null;
		try {
			breakdownRepository.saveAndFlush(new ImpactAssessmentBreakdown(
					assessment, f.impact(), 3, 80, ImpactBand.CRITICAL, 1));
		} catch (org.springframework.dao.DataIntegrityViolationException ex) {
			thrown = ex;
		}
		assertThat(thrown).isNotNull();
	}

	@Test
	void versionsMustDiffer() {
		Fixture f = fixture();
		org.springframework.dao.DataIntegrityViolationException thrown = null;
		try {
			assessmentRepository.saveAndFlush(new ImpactAssessment(
					f.user(), f.v1(), f.v1(), 0, ImpactBand.NONE, 1));
		} catch (org.springframework.dao.DataIntegrityViolationException ex) {
			thrown = ex;
		}
		assertThat(thrown).isNotNull();
	}

	@Test
	void userScopedQueries() {
		Fixture f = fixture();
		User other = userRepository.saveAndFlush(new User());
		ImpactAssessment owned = assessmentRepository.saveAndFlush(new ImpactAssessment(
				f.user(), f.v2(), f.v1(), 80, ImpactBand.CRITICAL, 1));
		assertThat(assessmentRepository.findByUser_IdAndNewVersion_Id(
				f.user().getId(), f.v2().getId())).isPresent();
		assertThat(assessmentRepository.findByUser_IdAndNewVersion_Id(
				other.getId(), f.v2().getId())).isEmpty();
		assertThat(assessmentRepository.findByUser_IdOrderByCreatedAtDesc(f.user().getId()))
				.extracting(a -> a.getId()).containsExactly(owned.getId());
		assertThat(assessmentRepository.findByUser_IdOrderByCreatedAtDesc(other.getId())).isEmpty();
	}

	@Test
	void immutabilityConventions() {
		for (Class<?> type : List.of(ImpactAssessment.class, ImpactAssessmentBreakdown.class)) {
			List<String> setters = Arrays.stream(type.getMethods()).map(Method::getName)
					.filter(n -> n.startsWith("set")).toList();
			assertThat(setters).as("%s setters", type.getSimpleName()).isEmpty();
			Arrays.stream(type.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Column.class))
					.forEach(f -> assertThat(f.getAnnotation(Column.class).updatable())
							.as("Column %s", f.getName()).isFalse());
			Arrays.stream(type.getDeclaredFields()).filter(f -> f.isAnnotationPresent(JoinColumn.class))
					.forEach(f -> assertThat(f.getAnnotation(JoinColumn.class).updatable())
							.as("Join %s", f.getName()).isFalse());
		}
	}

	private record Fixture(User user, PolicyVersion v1, PolicyVersion v2, ChangeImpact impact) {
	}

	private Fixture fixture() {
		PrivacyConcept concept = conceptRepository.findByCode("LOCATION").orElseThrow();
		Policy policy = policyRepository.saveAndFlush(
				new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		String oldText = "old-" + UUID.randomUUID();
		String newText = "new location data " + UUID.randomUUID();
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, oldText, hasher.hash(oldText)));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, newText, hasher.hash(newText)));
		PolicyChangeRecord change = changeRepository.saveAndFlush(
				new PolicyChangeRecord(v1, v2, PolicyChangeType.MODIFIED, oldText, newText, 0));
		ChangeConceptMatch match = matchRepository.saveAndFlush(
				new ChangeConceptMatch(change, concept, "location", "LOCATION:location", "KEYWORD"));
		ChangeImpact impact = impactRepository.saveAndFlush(new ChangeImpact(match, "LOCATION",
				"MODIFIED", 8, new BigDecimal("1.00"), new BigDecimal("8.00"),
				80, ImpactBand.CRITICAL, 1));
		User user = userRepository.saveAndFlush(new User());
		return new Fixture(user, v1, v2, impact);
	}
}

