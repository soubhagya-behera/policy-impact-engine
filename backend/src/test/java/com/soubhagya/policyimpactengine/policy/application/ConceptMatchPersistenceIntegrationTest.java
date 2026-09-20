package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Random;

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
import com.soubhagya.policyimpactengine.intelligence.ConceptMatcher;
import com.soubhagya.policyimpactengine.intelligence.DeterministicConceptMatcher;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
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
 * Phase 2N — end-to-end concept-match tests against Testcontainers PostgreSQL.
 *
 * <p>Proves FIRST_VERSION/UNCHANGED produce no matches, NEW_VERSION persists
 * expected concept matches with correct evidence and deterministic ordering,
 * SimHash stays present, and transaction rollback on matcher failure leaves
 * no partial version/change/match rows.
 */
@SpringBootTest
@Testcontainers
class ConceptMatchPersistenceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private PolicyRepository policyRepository;
	@Autowired
	private PolicyVersionRepository versionRepository;
	@Autowired
	private PolicyChangeRecordRepository changeRepository;
	@Autowired
	private PrivacyConceptRepository conceptRepository;
	@Autowired
	private ChangeConceptMatchRepository matchRepository;
	@Autowired
	private PolicyVersionService versionService;
	@Autowired
	private PolicyFetchAttemptService attemptService;
	@Autowired
	private PolicyFetchAttemptRepository attemptRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	private RetryPolicy testRetryPolicy() {
		return new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random());
	}
	private final PolicyDiffEngine diffEngine = new LineBasedPolicyDiffEngine();
	private final ConceptMatcher conceptMatcher = new DeterministicConceptMatcher();

	@BeforeEach
	void clean() {
		attemptRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void firstVersionHasNoConceptMatches() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(diffEngine, conceptMatcher);

		PolicyObservationResult first = persistence.store(policy.getId(), "first content", hasher.hash("first content"));

		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(first.diff()).isEmpty();
		assertThat(first.similarity()).isEmpty();
		assertThat(matchRepository.count()).isZero();
		assertThat(changeRepository.count()).isZero();
	}

	@Test
	void unchangedHasNoConceptMatches() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(diffEngine, conceptMatcher);
		persistence.store(policy.getId(), "same content", hasher.hash("same content"));

		PolicyObservationResult second = persistence.store(policy.getId(), "same content", hasher.hash("same content"));

		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(versionRepository.count()).isEqualTo(1);
		assertThat(matchRepository.count()).isZero();
		assertThat(changeRepository.count()).isZero();
		assertThat(second.similarity()).isEmpty();
	}

	@Test
	void newVersionPersistsConceptMatchesWithEvidenceAndDeterministicOrdering() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(diffEngine, conceptMatcher);
		persistence.store(policy.getId(), "Privacy Policy\nWe collect data.", hasher.hash("Privacy Policy\nWe collect data."));

		String newContent = "Privacy Policy\nWe collect your precise location data and share it with third-party advertising partners. We retain data for 90 days.";
		PolicyObservationResult result = persistence.store(policy.getId(), newContent, hasher.hash(newContent));

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.diff()).isPresent();
		assertThat(result.similarity()).isPresent();
		PolicyVersion v2 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		List<com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord> changes = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(v2.getId());
		assertThat(changes).isNotEmpty();

		List<ChangeConceptMatch> allMatches = new java.util.ArrayList<>();
		for (var ch : changes) {
			allMatches.addAll(matchRepository.findByChange_IdOrderByConcept_CodeAsc(ch.getId()));
		}
		assertThat(allMatches).isNotEmpty();
		// Expect at least LOCATION, THIRD_PARTY_SHARING, ADVERTISING, DATA_RETENTION
		assertThat(allMatches).extracting(m -> m.getConcept().getCode())
				.contains("LOCATION", "THIRD_PARTY_SHARING", "ADVERTISING", "DATA_RETENTION");
		// Deterministic ordering within each change by concept code
		for (var ch : changes) {
			List<ChangeConceptMatch> perChange = matchRepository.findByChange_IdOrderByConcept_CodeAsc(ch.getId());
			assertThat(perChange).extracting(m -> m.getConcept().getCode()).isSorted();
			for (ChangeConceptMatch m : perChange) {
				String text = (ch.getNewText() != null ? ch.getNewText() : "") + (ch.getOldText() != null ? ch.getOldText() : "");
				assertThat(text).contains(m.getMatchedFragment());
				assertThat(m.getPatternId()).isNotBlank();
				assertThat(m.getMatchKind()).isIn("KEYWORD", "PHRASE");
				assertThat(m.getChange().getId()).isEqualTo(ch.getId());
			}
		}
	}

	@Test
	void fullObservationFlowViaOrchestratorWithConceptMatches() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		String firstHtml = "<html><body><h1>Privacy Policy</h1><p>We collect data.</p><p>This policy applies to all users.</p></body></html>";
		String changedHtml = "<html><body><h1>Privacy Policy</h1>"
				+ "<p>We collect your precise location data and share it with third-party advertising partners.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>We retain data for 90 days and you have the right to be forgotten.</p></body></html>";

		PolicyObservationPersistenceService persistence = persistenceService(diffEngine, conceptMatcher);
		PolicyObservationService orchestrator = new PolicyObservationService(policyRepository,
				new StubFetcher(firstHtml), extractor, normalizer, hasher, persistence, attemptService, testRetryPolicy(), transactionManager);

		PolicyObservationResult first = orchestrator.observe(policy.getId());
		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(matchRepository.count()).isZero();

		StubFetcher secondFetcher = new StubFetcher(firstHtml);
		PolicyObservationService orch2 = new PolicyObservationService(policyRepository, secondFetcher, extractor, normalizer, hasher, persistence, attemptService, testRetryPolicy(), transactionManager);
		// Formatting unchanged check: second observation with same content -> UNCHANGED
		PolicyObservationResult unchanged = orch2.observe(policy.getId());
		assertThat(unchanged.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(matchRepository.count()).isZero();

		StubFetcher changedFetcher = new StubFetcher(changedHtml);
		PolicyObservationService orch3 = new PolicyObservationService(policyRepository, changedFetcher, extractor, normalizer, hasher, persistence, attemptService, testRetryPolicy(), transactionManager);
		PolicyObservationResult changed = orch3.observe(policy.getId());
		assertThat(changed.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(changed.similarity()).isPresent();
		assertThat(matchRepository.count()).isGreaterThan(0);
		assertThat(matchRepository.findAll()).extracting(m -> m.getConcept().getCode())
				.contains("LOCATION", "THIRD_PARTY_SHARING");

		// Repeat same changed content -> UNCHANGED, no new matches
		long matchesBefore = matchRepository.count();
		PolicyObservationResult repeat = orch3.observe(policy.getId());
		assertThat(repeat.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(matchRepository.count()).isEqualTo(matchesBefore);
		assertThat(versionRepository.count()).isEqualTo(2);
	}

	// Helper to access fetcher via reflection not needed; we just create new orchestrator each time.

	@Test
	void conceptMatchFailureRollsBackVersionAndChanges() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService realPersistence = persistenceService(diffEngine, conceptMatcher);
		realPersistence.store(policy.getId(), "first content", hasher.hash("first content"));

		ConceptMatcher failingMatcher = change -> {
			throw new IllegalStateException("matcher failed");
		};
		PolicyObservationPersistenceService failingPersistence = persistenceService(diffEngine, failingMatcher);

		assertThatThrownBy(() -> failingPersistence.store(policy.getId(), "second location data content",
				hasher.hash("second location data content"))).isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("matcher failed");

		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId())).hasSize(1);
		assertThat(changeRepository.count()).isZero();
		assertThat(matchRepository.count()).isZero();
	}

	@Test
	void simHashRemainsPresentAndUnchangedWithConceptMatching() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(diffEngine, conceptMatcher);
		persistence.store(policy.getId(), "Privacy Policy\nWe collect data.", hasher.hash("Privacy Policy\nWe collect data."));
		PolicyObservationResult result = persistence.store(policy.getId(),
				"Privacy Policy\nWe collect location data.", hasher.hash("Privacy Policy\nWe collect location data."));
		assertThat(result.similarity()).isPresent();
		assertThat(result.similarity().orElseThrow().similarity()).isBetween(0.0, 1.0);
		// Concept matches also persisted alongside similarity
		assertThat(matchRepository.count()).isGreaterThan(0);
	}

	private PolicyObservationPersistenceService persistenceService(PolicyDiffEngine engine, ConceptMatcher matcher) {
		return new PolicyObservationPersistenceService(versionService, versionRepository, engine, changeRepository,
				new DefaultPolicySimHash(), conceptRepository, matchRepository, matcher, transactionManager);
	}

	static class StubFetcher implements PolicyFetcher {
		private String html;
		StubFetcher(String html) { this.html = html; }
		void setHtml(String h) { this.html = h; }
		@Override
		public FetchResult fetch(String url) { return new FetchResult(url, 200, "text/html", html); }
	}
}
