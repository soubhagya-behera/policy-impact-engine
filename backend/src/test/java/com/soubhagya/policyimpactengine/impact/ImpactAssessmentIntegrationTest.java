package com.soubhagya.policyimpactengine.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.DefaultPolicySimHash;
import com.soubhagya.policyimpactengine.diff.LineBasedPolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.DeterministicConceptMatcher;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.user.UserPrivacyPreferenceService;
import com.soubhagya.policyimpactengine.user.UserService;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;


/**
 * Phase 2Q — two-user isolation + idempotency + traceability.
 */
@SpringBootTest
@Testcontainers
class ImpactAssessmentIntegrationTest {

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
	@Autowired private ImpactAssessmentBreakdownRepository breakdownRepository;
	@Autowired private PolicyVersionService versionService;
	@Autowired private com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository preferenceRepository;
	@Autowired private UserService userService;
	@Autowired private UserPrivacyPreferenceService preferenceService;
	@Autowired private ImpactAssessmentService assessmentService;
	@Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();
	private final PolicyDiffEngine diffEngine = new LineBasedPolicyDiffEngine();
	private final DeterministicConceptMatcher conceptMatcher = new DeterministicConceptMatcher();
	private final DeterministicImpactScoringEngine scoringEngine = new DeterministicImpactScoringEngine();

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
	void twoUsersDivergeOnSameGlobalImpacts() {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = new PolicyObservationPersistenceService(
				versionService, versionRepository, diffEngine, changeRepository,
				new DefaultPolicySimHash(), conceptRepository, matchRepository, conceptMatcher,
				impactRepository, scoringEngine, transactionManager);
		String first = "We respect your privacy.\nWe retain data for 30 days.\n";
		persistence.store(policy.getId(), first, hasher.hash(first));
		String second = "We collect precise location data and share it with advertising partners.\n";
		persistence.store(policy.getId(), second, hasher.hash(second));
		PolicyVersion v2 = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		User a = userService.createUser();
		User b = userService.createUser();
		preferenceService.upsertPreference(a.getId(), "LOCATION", 5);
		ImpactAssessment assessmentA = assessmentService.getOrCreateAssessment(a.getId(), v2.getId());
		ImpactAssessment assessmentB = assessmentService.getOrCreateAssessment(b.getId(), v2.getId());
		List<ImpactAssessmentBreakdown> breakdownsA = assessmentService.listBreakdowns(a.getId(), v2.getId());
		List<ImpactAssessmentBreakdown> breakdownsB = assessmentService.listBreakdowns(b.getId(), v2.getId());
		assertThat(breakdownsA).isNotEmpty();
		assertThat(breakdownsA.size()).isEqualTo(breakdownsB.size());
		ImpactAssessmentBreakdown locationA = breakdownsA.stream()
				.filter(x -> x.getConceptCode().equals("LOCATION")).findFirst().orElseThrow();
		assertThat(locationA.getEffectiveSensitivity()).isEqualTo(5);
		ImpactAssessmentBreakdown locationB = breakdownsB.stream()
				.filter(x -> x.getConceptCode().equals("LOCATION")).findFirst().orElseThrow();
		assertThat(locationB.getEffectiveSensitivity()).isEqualTo(3);
		assertThat(locationA.getPersonalizedNormalized()).isGreaterThan(locationB.getPersonalizedNormalized());
		java.util.Map<String, Integer> expectedA = new java.util.LinkedHashMap<>();
		java.util.Map<String, Integer> expectedB = new java.util.LinkedHashMap<>();
		for (ImpactAssessmentBreakdown bd : breakdownsA) {
			expectedA.put(bd.getConceptCode(), bd.getPersonalizedNormalized());
		}
		for (ImpactAssessmentBreakdown bd : breakdownsB) {
			expectedB.put(bd.getConceptCode(), bd.getPersonalizedNormalized());
		}
		assertThat(expectedA.get("LOCATION")).isEqualTo(
				Math.min(100, (int) Math.round(locationA.getChangeImpact().getBaseScore().doubleValue() * 5 / 3.0 * 10)));
		assertThat(expectedB.get("LOCATION")).isEqualTo(
				locationB.getChangeImpact().getNormalizedScore());
		assertThat(assessmentA.getAggregateScore()).isEqualTo(
				expectedA.values().stream().mapToInt(Integer::intValue).max().orElse(0));
		assertThat(assessmentB.getAggregateScore()).isEqualTo(
				expectedB.values().stream().mapToInt(Integer::intValue).max().orElse(0));
		assertThat(assessmentA.getAggregateScore()).isGreaterThanOrEqualTo(assessmentB.getAggregateScore());
		long globalImpacts = impactRepository.count();
		assertThat(globalImpacts).isGreaterThan(0);
		ImpactAssessment repeatA = assessmentService.getOrCreateAssessment(a.getId(), v2.getId());
		assertThat(repeatA.getId()).isEqualTo(assessmentA.getId());
		assertThat(assessmentRepository.count()).isEqualTo(2);
		assertThat(impactRepository.count()).isEqualTo(globalImpacts);
		for (ImpactAssessmentBreakdown bd : breakdownsA) {
			assertThat(bd.getChangeImpact().getId()).isNotNull();
			assertThat(bd.getChangeImpact().getMatch().getId()).isNotNull();
			assertThat(bd.getChangeImpact().getMatch().getChange().getId()).isNotNull();
			assertThat(bd.getChangeImpact().getMatch().getChange().getNewVersion().getId())
					.isEqualTo(v2.getId());
		}
		assertThatThrownBy(() -> assessmentService.listBreakdowns(a.getId(), java.util.UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void zeroSensitivitySuppressesWhileFiveAmplifies() {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("Cookie Policy", "https://example.com/cookies"));
		PolicyObservationPersistenceService persistence = new PolicyObservationPersistenceService(
				versionService, versionRepository, diffEngine, changeRepository,
				new DefaultPolicySimHash(), conceptRepository, matchRepository, conceptMatcher,
				impactRepository, scoringEngine, transactionManager);
		String first = "We respect your privacy.\n";
		persistence.store(policy.getId(), first, hasher.hash(first));
		String second = "We use cookies for tracking and precise location data collection.\n";
		persistence.store(policy.getId(), second, hasher.hash(second));
		PolicyVersion v2 = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		User a = userService.createUser();
		preferenceService.upsertPreference(a.getId(), "COOKIES", 0);
		preferenceService.upsertPreference(a.getId(), "LOCATION", 5);
		assessmentService.getOrCreateAssessment(a.getId(), v2.getId());
		List<ImpactAssessmentBreakdown> breakdowns = assessmentService
				.listBreakdowns(a.getId(), v2.getId());
		assertThat(breakdowns).isNotEmpty();
		for (ImpactAssessmentBreakdown bd : breakdowns) {
			if (bd.getConceptCode().equals("COOKIES")) {
				assertThat(bd.getEffectiveSensitivity()).isZero();
				assertThat(bd.getPersonalizedNormalized()).isZero();
				assertThat(bd.getPersonalizedBand()).isEqualTo(ImpactBand.NONE);
			}
			if (bd.getConceptCode().equals("LOCATION")) {
				assertThat(bd.getEffectiveSensitivity()).isEqualTo(5);
				assertThat(bd.getPersonalizedNormalized()).isGreaterThan(0);
			}
		}
	}

	@Test
	void emptyTransitionPersistsZeroNone() {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("Empty Policy", "https://example.com/empty"));
		PolicyObservationPersistenceService persistence = new PolicyObservationPersistenceService(
				versionService, versionRepository, diffEngine, changeRepository,
				new DefaultPolicySimHash(), conceptRepository, matchRepository, conceptMatcher,
				impactRepository, scoringEngine, transactionManager);
		String first = "We respect your privacy.\n";
		persistence.store(policy.getId(), first, hasher.hash(first));
		String second = "Completely different wording with no concept keywords at all xyzzy qqqq.\n";
		persistence.store(policy.getId(), second, hasher.hash(second));
		PolicyVersion v2 = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		User user = userService.createUser();
		ImpactAssessment assessment = assessmentService.getOrCreateAssessment(user.getId(), v2.getId());
		assertThat(assessment.getAggregateScore()).isZero();
		assertThat(assessment.getAggregateBand()).isEqualTo(ImpactBand.NONE);
		assertThat(assessmentService.listBreakdowns(user.getId(), v2.getId())).isEmpty();
	}

	@Test
	void firstVersionRejected() {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("First Policy", "https://example.com/first"));
		String first = "Initial content.\n";
		versionService.observe(policy.getId(), first, hasher.hash(first));
		PolicyVersion v1 = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 1).orElseThrow();
		User user = userService.createUser();
		assertThatThrownBy(() -> assessmentService.getOrCreateAssessment(user.getId(), v1.getId()))
				.isInstanceOf(IllegalArgumentException.class);
	}
}

