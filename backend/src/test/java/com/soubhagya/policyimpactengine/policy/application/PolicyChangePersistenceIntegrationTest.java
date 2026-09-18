package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
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
import com.soubhagya.policyimpactengine.diff.PolicyChange;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.PolicySimHash;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
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
 * Phase 2K — end-to-end change-persistence tests over the real deterministic
 * pipeline against Testcontainers PostgreSQL: stub HTTP (no internet) →
 * real extractor → real normalizer → real hasher → real repositories,
 * version service, diff engine, and the single version-plus-changes
 * transaction.
 */
@SpringBootTest
@Testcontainers
class PolicyChangePersistenceIntegrationTest {

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
	private PolicyVersionService versionService;
	@Autowired
	private PolicyFetchAttemptService attemptService;
	@Autowired
	private PolicyFetchAttemptRepository attemptRepository;

	@Autowired
	private PolicyDiffEngine diffEngine;

	@Autowired
	private PolicySimHash simHash;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void cleanDatabase() {
		attemptRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void versionOneHasNoChangeRecords() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(new LineBasedPolicyDiffEngine());

		PolicyObservationResult first = persistence.store(policy.getId(), "first content",
				hasher.hash("first content"));

		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		PolicyVersion v1 = versionRepository
				.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId()).orElseThrow();
		assertThat(changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v1.getId())).isEmpty();
	}

	@Test
	void unchangedObservationCreatesNoNewChangeRecords() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(new LineBasedPolicyDiffEngine());
		persistence.store(policy.getId(), "same content", hasher.hash("same content"));

		PolicyObservationResult second = persistence.store(policy.getId(), "same content",
				hasher.hash("same content"));

		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(versionRepository.count()).isEqualTo(1);
		PolicyVersion v1 = versionRepository
				.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId()).orElseThrow();
		assertThat(changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v1.getId())).isEmpty();
	}

	@Test
	void newVersionPersistsAllDiffChanges() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(new LineBasedPolicyDiffEngine());
		persistence.store(policy.getId(), "line one\nline two", hasher.hash("line one\nline two"));

		PolicyObservationResult result = persistence.store(policy.getId(), "line one\nline three",
				hasher.hash("line one\nline three"));

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.diff()).isPresent();
		PolicyVersion v2 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		List<PolicyChangeRecord> records = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(v2.getId());
		assertThat(records).hasSize(result.diff().orElseThrow().changes().size());
		assertThat(records).extracting(PolicyChangeRecord::getChangeOrder).containsExactly(0);
	}

	@Test
	void existingVersionOneRemainsImmutableWhenChangesPersisted() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(new LineBasedPolicyDiffEngine());
		persistence.store(policy.getId(), "first content", hasher.hash("first content"));
		PolicyVersion before = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1)
				.orElseThrow();
		String content = before.getNormalizedContent();
		String hash = before.getContentHash();
		Instant observedAt = before.getObservedAt();

		persistence.store(policy.getId(), "second content", hasher.hash("second content"));

		PolicyVersion after = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1)
				.orElseThrow();
		assertThat(after.getNormalizedContent()).isEqualTo(content);
		assertThat(after.getContentHash()).isEqualTo(hash);
		assertThat(after.getObservedAt()).isEqualTo(observedAt);
	}

	@Test
	void persistedChangeRecordsRemainImmutableThroughApplicationApi() {
		List<String> setters = Arrays.stream(PolicyChangeRecord.class.getMethods())
				.map(Method::getName)
				.filter(name -> name.startsWith("set"))
				.toList();
		assertThat(setters).isEmpty();

		List<String> persistenceOperations = Arrays.stream(PolicyObservationPersistenceService.class.getMethods())
				.map(Method::getName)
				.toList();
		assertThat(persistenceOperations).contains("store");
		assertThat(persistenceOperations).doesNotContain("update", "delete", "deleteById", "edit");
	}

	@Test
	void reobservingUnchangedContentDoesNotDuplicateChangeRecords() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(new LineBasedPolicyDiffEngine());
		persistence.store(policy.getId(), "v1 content", hasher.hash("v1 content"));
		persistence.store(policy.getId(), "v2 content", hasher.hash("v2 content"));
		PolicyVersion v2 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		long changesAfterV2 = changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v2.getId()).size();

		PolicyObservationResult repeated = persistence.store(policy.getId(), "v2 content",
				hasher.hash("v2 content"));

		assertThat(repeated.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v2.getId()))
				.hasSize((int) changesAfterV2);
		assertThat(versionRepository.count()).isEqualTo(2);
	}

	@Test
	void fullObservationFlowV1ToV2ProducesPersistedChangesMatchingDiffExactly() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		String firstHtml = "<html><body><h1>Privacy Policy</h1>"
				+ "<p>We collect your location data.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>We retain data for 30 days.</p></body></html>";
		String changedHtml = "<html><body><h1>Privacy Policy</h1>"
				+ "<p>We collect your precise location data.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>We retain data for 90 days.</p></body></html>";
		StubPolicyFetcher stubFetcher = new StubPolicyFetcher(firstHtml);
		PolicyObservationService orchestrator = observationService(stubFetcher, diffEngine);

		PolicyObservationResult first = orchestrator.observe(policy.getId());
		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(first.diff()).isEmpty();
		PolicyVersion v1 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1).orElseThrow();
		assertThat(changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v1.getId())).isEmpty();

		stubFetcher.setHtml(changedHtml);
		PolicyObservationResult second = orchestrator.observe(policy.getId());

		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(second.diff()).isPresent();
		PolicyDiffResult diff = second.diff().orElseThrow();

		PolicyVersion previous = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1)
				.orElseThrow();
		PolicyVersion created = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 2)
				.orElseThrow();
		List<PolicyChangeRecord> records = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(created.getId());

		assertThat(records).hasSize(diff.changes().size());
		for (int i = 0; i < diff.changes().size(); i++) {
			PolicyChange expected = diff.changes().get(i);
			PolicyChangeRecord actual = records.get(i);
			assertThat(actual.getChangeOrder()).isEqualTo(i);
			assertThat(actual.getChangeType()).isEqualTo(expected.type());
			assertThat(actual.getOldText()).isEqualTo(expected.oldText());
			assertThat(actual.getNewText()).isEqualTo(expected.newText());
			assertThat(actual.getPreviousVersion().getId()).isEqualTo(previous.getId());
			assertThat(actual.getNewVersion().getId()).isEqualTo(created.getId());
		}
	}

	@Test
	void multiplePolicyVersionsMaintainIndependentChangeHistories() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService persistence = persistenceService(new LineBasedPolicyDiffEngine());
		persistence.store(policy.getId(), "one", hasher.hash("one"));
		persistence.store(policy.getId(), "two", hasher.hash("two"));
		persistence.store(policy.getId(), "three", hasher.hash("three"));

		PolicyVersion v2 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		PolicyVersion v3 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 3).orElseThrow();
		List<PolicyChangeRecord> v2Changes = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(v2.getId());
		List<PolicyChangeRecord> v3Changes = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(v3.getId());

		assertThat(v2Changes).isNotEmpty();
		assertThat(v3Changes).isNotEmpty();
		assertThat(v2Changes).extracting(r -> r.getNewVersion().getId())
				.containsOnly(v2.getId());
		assertThat(v3Changes).extracting(r -> r.getNewVersion().getId())
				.containsOnly(v3.getId());
		assertThat(v2Changes).extracting(r -> r.getPreviousVersion().getId())
				.containsOnly(versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1)
						.orElseThrow().getId());
		assertThat(v3Changes).extracting(r -> r.getPreviousVersion().getId()).containsOnly(v2.getId());
	}

	@Test
	void newVersionWithEmptyDiffPersistsZeroRowsExplicitly() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService realPersistence = persistenceService(
				new LineBasedPolicyDiffEngine());
		realPersistence.store(policy.getId(), "first content", hasher.hash("first content"));

		PolicyObservationPersistenceService emptyDiffPersistence = persistenceService(
				(oldContent, newContent) -> new PolicyDiffResult(List.of()));
		PolicyObservationResult result = emptyDiffPersistence.store(policy.getId(), "second content",
				hasher.hash("second content"));

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.diff()).isPresent();
		assertThat(result.diff().orElseThrow().changes()).isEmpty();
		PolicyVersion v2 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow();
		assertThat(changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v2.getId())).isEmpty();
	}

	@Test
	void diffFailureRollsBackVersionSoNoPartialStateRemains() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyObservationPersistenceService realPersistence = persistenceService(
				new LineBasedPolicyDiffEngine());
		realPersistence.store(policy.getId(), "first content", hasher.hash("first content"));

		PolicyObservationPersistenceService failingPersistence = persistenceService(
				(oldContent, newContent) -> {
					throw new IllegalStateException("diff failed");
				});

		assertThatThrownBy(() -> failingPersistence.store(policy.getId(), "second content",
				hasher.hash("second content")))
						.isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("diff failed");

		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId())).hasSize(1);
		PolicyVersion v1 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1).orElseThrow();
		assertThat(changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v1.getId())).isEmpty();
	}

	private PolicyObservationPersistenceService persistenceService(PolicyDiffEngine engine) {
		return new PolicyObservationPersistenceService(versionService, versionRepository, engine,
				changeRepository, simHash != null ? simHash : new DefaultPolicySimHash(), transactionManager);
	}

	private PolicyObservationService observationService(PolicyFetcher fetcher, PolicyDiffEngine engine) {
		return new PolicyObservationService(policyRepository, fetcher, extractor, normalizer, hasher,
				persistenceService(engine), attemptService);
	}

	static class StubPolicyFetcher implements PolicyFetcher {

		private String html;

		StubPolicyFetcher(String html) {
			this.html = html;
		}

		void setHtml(String html) {
			this.html = html;
		}

		@Override
		public FetchResult fetch(String url) {
			return new FetchResult(url, 200, "text/html", html);
		}
	}

}
