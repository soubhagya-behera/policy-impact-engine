package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.DefaultPolicySimHash;
import com.soubhagya.policyimpactengine.diff.LineBasedPolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.DeterministicImpactScoringEngine;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.ConceptMatcher;
import com.soubhagya.policyimpactengine.intelligence.DeterministicConceptMatcher;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.JsoupPolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

/**
 * Phase 2O — end-to-end system-level impact tests.
 */
@SpringBootTest
@Testcontainers
class ImpactPersistenceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private PolicyChangeRecordRepository changeRepository;
	@Autowired private PrivacyConceptRepository conceptRepository;
	@Autowired private ChangeConceptMatchRepository matchRepository;
	@Autowired private ChangeImpactRepository impactRepository;
	@Autowired 	private PolicyVersionService versionService;
	@Autowired
	private PolicyFetchAttemptService attemptService;
	@Autowired
	private PolicyFetchAttemptRepository attemptRepository;
	@Autowired private PlatformTransactionManager transactionManager;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();
	private final PolicyDiffEngine diffEngine = new LineBasedPolicyDiffEngine();
	private final ConceptMatcher conceptMatcher = new DeterministicConceptMatcher();
	private final com.soubhagya.policyimpactengine.impact.ImpactScoringEngine scoringEngine = new DeterministicImpactScoringEngine();

	@BeforeEach
	void clean() {
		attemptRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void firstVersionHasNoImpacts() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService p = persistenceService(diffEngine, conceptMatcher, scoringEngine);
		PolicyObservationResult first = p.store(policy.getId(), "first content", hasher.hash("first content"));
		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(impactRepository.count()).isZero();
		assertThat(matchRepository.count()).isZero();
		assertThat(first.similarity()).isEmpty();
	}

	@Test
	void unchangedHasNoImpacts() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService p = persistenceService(diffEngine, conceptMatcher, scoringEngine);
		p.store(policy.getId(), "same content", hasher.hash("same content"));
		PolicyObservationResult second = p.store(policy.getId(), "same content", hasher.hash("same content"));
		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(impactRepository.count()).isZero();
		assertThat(second.similarity()).isEmpty();
	}

	@Test
	void newVersionPersistsImpactsWithTraceableEvidence() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService p = persistenceService(diffEngine, conceptMatcher, scoringEngine);
		p.store(policy.getId(), "Privacy Policy\nWe collect data.", hasher.hash("Privacy Policy\nWe collect data."));
		String newContent = "Privacy Policy\nWe collect your precise location data and share it with third-party advertising partners. We retain data for 90 days.";
		PolicyObservationResult result = p.store(policy.getId(), newContent, hasher.hash(newContent));
		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.similarity()).isPresent();
		List<ChangeImpact> impacts = impactRepository.findAll();
		assertThat(impacts).isNotEmpty();
		assertThat(impacts).extracting(ChangeImpact::getConceptCode).contains("LOCATION", "THIRD_PARTY_SHARING", "ADVERTISING", "DATA_RETENTION");
		for (ChangeImpact imp : impacts) {
			assertThat(imp.getConceptWeight()).isGreaterThan(0);
			assertThat(imp.getChangeTypeMultiplier().doubleValue()).isIn(0.6, 0.8, 1.0);
			assertThat(imp.getBaseScore().doubleValue()).isEqualTo(imp.getConceptWeight() * imp.getChangeTypeMultiplier().doubleValue(), org.assertj.core.api.Assertions.within(0.01));
			assertThat(imp.getNormalizedScore()).isEqualTo(Math.min(100, (int) Math.round(imp.getBaseScore().doubleValue() * 10)));
			assertThat(imp.getImpactBand()).isEqualTo(ImpactBand.fromNormalizedScore(imp.getNormalizedScore()));
			assertThat(imp.getRulesVersion()).isEqualTo(1);
			// Traceability: impact → match → change → old/new text contains matched fragment
			assertThat(imp.getMatch()).isNotNull();
			assertThat(imp.getMatch().getMatchedFragment()).isNotBlank();
			String changeText = imp.getMatch().getChange().getNewText() != null ? imp.getMatch().getChange().getNewText() : imp.getMatch().getChange().getOldText();
			// matchedFragment must be substring of either old or new of the change
			boolean inOld = imp.getMatch().getChange().getOldText() != null && imp.getMatch().getChange().getOldText().contains(imp.getMatch().getMatchedFragment());
			boolean inNew = imp.getMatch().getChange().getNewText() != null && imp.getMatch().getChange().getNewText().contains(imp.getMatch().getMatchedFragment());
			assertThat(inOld || inNew).isTrue();
		}
		// Specific band expectations
		ChangeImpact loc = impacts.stream().filter(i -> i.getConceptCode().equals("LOCATION")).findFirst().orElseThrow();
		assertThat(loc.getChangeType()).isEqualTo("MODIFIED");
		assertThat(loc.getConceptWeight()).isEqualTo(8);
		assertThat(loc.getBaseScore().doubleValue()).isEqualTo(8.0, org.assertj.core.api.Assertions.within(0.01));
		assertThat(loc.getNormalizedScore()).isEqualTo(80);
		assertThat(loc.getImpactBand()).isEqualTo(ImpactBand.CRITICAL);
	}

	@Test
	void newVersionWithZeroChangesHasZeroImpacts() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyDiffEngine emptyDiff = (oldC, newC) -> new PolicyDiffResult(List.of());
		PolicyObservationPersistenceService p = persistenceService(emptyDiff, conceptMatcher, scoringEngine);
		p.store(policy.getId(), "first content", hasher.hash("first content"));
		PolicyObservationResult result = p.store(policy.getId(), "second content", hasher.hash("second content"));
		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(impactRepository.count()).isZero();
	}

	@Test
	void newVersionWithChangesButZeroMatchesHasZeroImpacts() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService p = persistenceService(diffEngine, conceptMatcher, scoringEngine);
		p.store(policy.getId(), "hello world", hasher.hash("hello world"));
		PolicyObservationResult result = p.store(policy.getId(), "hello galaxy", hasher.hash("hello galaxy"));
		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(changeRepository.count()).isGreaterThan(0);
		assertThat(matchRepository.count()).isZero();
		assertThat(impactRepository.count()).isZero();
	}

	@Test
	void repeatUnchangedProducesNoAdditionalImpacts() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService p = persistenceService(diffEngine, conceptMatcher, scoringEngine);
		p.store(policy.getId(), "Privacy Policy\nWe collect data.", hasher.hash("Privacy Policy\nWe collect data."));
		p.store(policy.getId(), "Privacy Policy\nWe collect location data.", hasher.hash("Privacy Policy\nWe collect location data."));
		long impactsBefore = impactRepository.count();
		long matchesBefore = matchRepository.count();
		long versionsBefore = versionRepository.count();
		PolicyObservationResult repeat = p.store(policy.getId(), "Privacy Policy\nWe collect location data.", hasher.hash("Privacy Policy\nWe collect location data."));
		assertThat(repeat.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(impactRepository.count()).isEqualTo(impactsBefore);
		assertThat(matchRepository.count()).isEqualTo(matchesBefore);
		assertThat(versionRepository.count()).isEqualTo(versionsBefore);
	}

	@Test
	void fullOrchestratorFlowWithSimHashPreserved() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		String firstHtml = "<html><body><h1>Privacy Policy</h1><p>We collect data.</p></body></html>";
		String changedHtml = "<html><body><h1>Privacy Policy</h1><p>We collect your precise location data and share it with third-party advertising partners.</p><p>We retain data for 90 days.</p></body></html>";
		PolicyObservationPersistenceService persistence = persistenceService(diffEngine, conceptMatcher, scoringEngine);
		PolicyObservationService orch = new PolicyObservationService(policyRepository, new StubFetcher(firstHtml), extractor, normalizer, hasher, persistence, attemptService);
		PolicyObservationResult first = orch.observe(policy.getId());
		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(impactRepository.count()).isZero();
		PolicyObservationService orch2 = new PolicyObservationService(policyRepository, new StubFetcher(changedHtml), extractor, normalizer, hasher, persistence, attemptService);
		PolicyObservationResult changed = orch2.observe(policy.getId());
		assertThat(changed.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(changed.similarity()).isPresent();
		assertThat(impactRepository.count()).isGreaterThan(0);
		PolicyObservationService orch3 = new PolicyObservationService(policyRepository, new StubFetcher(changedHtml), extractor, normalizer, hasher, persistence, attemptService);
		PolicyObservationResult repeat = orch3.observe(policy.getId());
		assertThat(repeat.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(impactRepository.count()).isEqualTo(impactRepository.count());
	}

	@Test
	void impactScoringFailureRollsBackAll() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService real = persistenceService(diffEngine, conceptMatcher, scoringEngine);
		real.store(policy.getId(), "first content", hasher.hash("first content"));
		com.soubhagya.policyimpactengine.impact.ImpactScoringEngine failing = new com.soubhagya.policyimpactengine.impact.ImpactScoringEngine() {
			@Override public com.soubhagya.policyimpactengine.impact.domain.ImpactScore score(com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord c, com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch m) { throw new IllegalStateException("scorer failed"); }
			@Override public List<com.soubhagya.policyimpactengine.impact.domain.ImpactScore> scoreAll(List<com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord> a, List<com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch> b) { throw new IllegalStateException("scorer failed"); }
		};
		PolicyObservationPersistenceService failingP = persistenceService(diffEngine, conceptMatcher, failing);
		assertThatThrownBy(() -> failingP.store(policy.getId(), "second location data content", hasher.hash("second location data content")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("scorer failed");
		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId())).hasSize(1);
		assertThat(changeRepository.count()).isZero();
		assertThat(matchRepository.count()).isZero();
		assertThat(impactRepository.count()).isZero();
	}

	private PolicyObservationPersistenceService persistenceService(PolicyDiffEngine engine, ConceptMatcher matcher, com.soubhagya.policyimpactengine.impact.ImpactScoringEngine scorer) {
		return new PolicyObservationPersistenceService(versionService, versionRepository, engine, changeRepository,
				new DefaultPolicySimHash(), conceptRepository, matchRepository, matcher, impactRepository, scorer, transactionManager);
	}

	static class StubFetcher implements PolicyFetcher {
		private String html;
		StubFetcher(String html) { this.html = html; }
		@Override public FetchResult fetch(String url) { return new FetchResult(url, 200, "text/html", html); }
	}
}
