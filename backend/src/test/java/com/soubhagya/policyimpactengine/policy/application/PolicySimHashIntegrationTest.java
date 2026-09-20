package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.soubhagya.policyimpactengine.diff.PolicySimHash;
import com.soubhagya.policyimpactengine.diff.SimHashDistance;
import com.soubhagya.policyimpactengine.diff.SimHashSimilarity;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
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
 * Phase 2M — PostgreSQL integration test for SimHash similarity signal.
 *
 * <p>Uses real PostgreSQL, real PolicyObservationService,
 * real PolicyObservationPersistenceService, real PolicyVersionService,
 * real PolicyDiffEngine, real PolicySimHash, real normalizer, real
 * extractor, real hasher, deterministic stub PolicyFetcher.
 *
 * <p>Scenario:
 * Observation 1 → FIRST_VERSION no similarity.
 * Observation 2 → formatting-only equivalent content, SHA-256 unchanged → UNCHANGED no similarity.
 * Observation 3 → wording changed → NEW_VERSION diff exists similarity exists.
 *
 * <p>Verifies SHA-256 unchanged, versions 1 and 2 correct, PolicyChange
 * records unchanged, similarity deterministic, persisted version/change
 * data unaffected.
 */
@SpringBootTest
@Testcontainers
class PolicySimHashIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private PolicyVersionService versionService;
	@Autowired
	private PolicyFetchAttemptService attemptService;
	@Autowired
	private PolicyFetchAttemptRepository attemptRepository;

	@Autowired
	private PolicyChangeRecordRepository changeRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private PolicySimHash injectedSimHash;

	@Autowired
	private PolicyDiffEngine injectedDiffEngine;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	private RetryPolicy testRetryPolicy() {
		return new RetryPolicy(5, Duration.ofMinutes(5), 2.0,
				Duration.ofHours(6), Duration.ofHours(24), new Random());
	}

	@BeforeEach
	void cleanDatabase() {
		attemptRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void simHashSimilarityIntegratedEndToEnd() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));

		String firstHtml = "<html><body><h1>Privacy Policy</h1>"
				+ "<p>We collect your location data.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>We retain data for 30 days.</p></body></html>";
		String reformattedHtml = "<html><head><script>var x = 1;</script></head><body>"
				+ "<h1>  Privacy   Policy </h1>"
				+ "<p>We collect your location data.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>\n  We retain data for 30 days.  \n</p></body></html>";
		String changedHtml = "<html><body><h1>Privacy Policy</h1>"
				+ "<p>We collect your precise location data.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>We retain data for 90 days.</p></body></html>";

		String firstNormalized = normalizer.normalize(extractor.extract(firstHtml));
		String reformattedNormalized = normalizer.normalize(extractor.extract(reformattedHtml));
		assertThat(reformattedNormalized).isEqualTo(firstNormalized);
		String changedNormalized = normalizer.normalize(extractor.extract(changedHtml));
		assertThat(changedNormalized).isNotEqualTo(firstNormalized);

		// Use counting decorators to prove SimHash invocation counts
		CountingDiffEngine countingDiff = new CountingDiffEngine(injectedDiffEngine);
		CountingSimHash countingSimHash = new CountingSimHash(injectedSimHash);

		PolicyObservationPersistenceService persistenceService = new PolicyObservationPersistenceService(
				versionService, versionRepository, countingDiff, changeRepository, countingSimHash,
				transactionManager);
		StubPolicyFetcher stubFetcher = new StubPolicyFetcher(firstHtml);
		PolicyObservationService orchestrator = new PolicyObservationService(
				policyRepository, stubFetcher, extractor, normalizer, hasher, persistenceService, attemptService, testRetryPolicy(), transactionManager);

		// Observation 1: FIRST_VERSION no similarity
		PolicyObservationResult first = orchestrator.observe(policy.getId());
		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(first.versionNumber()).isEqualTo(1);
		assertThat(first.contentHash()).isEqualTo(hasher.hash(firstNormalized));
		assertThat(first.diff()).isEmpty();
		assertThat(first.similarity()).isEmpty();
		assertThat(countingDiff.invocations()).isZero();
		assertThat(countingSimHash.invocations()).isZero();
		assertThat(versionRepository.count()).isEqualTo(1);

		PolicyVersion v1 = versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1).orElseThrow();
		String v1Content = v1.getNormalizedContent();
		String v1Hash = v1.getContentHash();
		Instant v1ObservedAt = v1.getObservedAt();
		assertThat(v1Content).isEqualTo(firstNormalized);
		assertThat(changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v1.getId())).isEmpty();

		// Observation 2: formatting-only → UNCHANGED no similarity
		stubFetcher.setHtml(reformattedHtml);
		PolicyObservationResult second = orchestrator.observe(policy.getId());
		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(second.versionNumber()).isEqualTo(1);
		assertThat(second.contentHash()).isEqualTo(v1Hash);
		assertThat(second.diff()).isEmpty();
		assertThat(second.similarity()).isEmpty();
		assertThat(countingDiff.invocations()).isZero();
		assertThat(countingSimHash.invocations()).isZero();
		assertThat(versionRepository.count()).isEqualTo(1);

		// Observation 3: wording changed → NEW_VERSION diff + similarity
		stubFetcher.setHtml(changedHtml);
		PolicyObservationResult third = orchestrator.observe(policy.getId());
		assertThat(third.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(third.versionNumber()).isEqualTo(2);
		assertThat(third.contentHash()).isEqualTo(hasher.hash(changedNormalized));
		assertThat(third.diff()).isPresent();
		assertThat(third.diff().orElseThrow().changes()).hasSize(2);
		assertThat(third.similarity()).isPresent();
		assertThat(countingDiff.invocations()).isEqualTo(1);
		assertThat(countingSimHash.invocations()).isEqualTo(2); // previous + new

		SimHashSimilarity sim = third.similarity().orElseThrow();
		// Verify numerical correctness via real SimHash
		PolicySimHash realSimHash = new DefaultPolicySimHash();
		long expectedPrevHash = realSimHash.fingerprint(firstNormalized);
		long expectedNewHash = realSimHash.fingerprint(changedNormalized);
		int expectedDistance = SimHashDistance.hammingDistance(expectedPrevHash, expectedNewHash);
		double expectedSimilarity = SimHashDistance.similarity(expectedPrevHash, expectedNewHash);
		assertThat(sim.previousHash()).isEqualTo(expectedPrevHash);
		assertThat(sim.newHash()).isEqualTo(expectedNewHash);
		assertThat(sim.hammingDistance()).isEqualTo(expectedDistance);
		assertThat(sim.similarity()).isEqualTo(expectedSimilarity);
		// Deterministic: recomputed values match
		assertThat(realSimHash.fingerprint(firstNormalized)).isEqualTo(expectedPrevHash);
		assertThat(realSimHash.fingerprint(changedNormalized)).isEqualTo(expectedNewHash);
		assertThat(SimHashDistance.hammingDistance(expectedPrevHash, expectedNewHash))
				.isEqualTo(expectedDistance);

		// Verify persisted version/change data unaffected
		List<PolicyVersion> versions = versionRepository
				.findByPolicy_IdOrderByVersionNumberAsc(policy.getId());
		assertThat(versions).hasSize(2);
		assertThat(versions).extracting(PolicyVersion::getVersionNumber).containsExactly(1, 2);
		PolicyVersion persistedOne = versions.get(0);
		assertThat(persistedOne.getNormalizedContent()).isEqualTo(v1Content);
		assertThat(persistedOne.getContentHash()).isEqualTo(v1Hash);
		assertThat(persistedOne.getObservedAt()).isEqualTo(v1ObservedAt);
		PolicyVersion persistedTwo = versions.get(1);
		assertThat(persistedTwo.getNormalizedContent()).isEqualTo(changedNormalized);
		assertThat(persistedTwo.getContentHash()).isEqualTo(hasher.hash(changedNormalized));

		// PolicyChange records match diff exactly (existing 2K guarantee still holds)
		var persistedChanges = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(persistedTwo.getId());
		assertThat(persistedChanges).hasSize(third.diff().orElseThrow().changes().size());
		for (int i = 0; i < persistedChanges.size(); i++) {
			assertThat(persistedChanges.get(i).getChangeOrder()).isEqualTo(i);
		}

		// Verify no SimHash persistence side-effect: no extra version, no extra change row, no column
		assertThat(versionRepository.count()).isEqualTo(2);
		// Similarity is application-level only, not persisted; re-observing UNCHANGED does not add similarity
		stubFetcher.setHtml(changedHtml);
		PolicyObservationResult fourth = orchestrator.observe(policy.getId());
		assertThat(fourth.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(fourth.similarity()).isEmpty();
		assertThat(countingSimHash.invocations()).isEqualTo(2); // no extra invocation for UNCHANGED
	}

	/**
	 * Counts diff invocations while delegating to the real engine.
	 */
	static class CountingDiffEngine implements PolicyDiffEngine {
		private final PolicyDiffEngine delegate;
		private final AtomicInteger invocations = new AtomicInteger();

		CountingDiffEngine(PolicyDiffEngine delegate) {
			this.delegate = delegate;
		}

		int invocations() {
			return invocations.get();
		}

		@Override
		public PolicyDiffResult diff(String oldContent, String newContent) {
			invocations.incrementAndGet();
			return delegate.diff(oldContent, newContent);
		}
	}

	/**
	 * Counts SimHash fingerprint invocations while delegating to the real SimHash.
	 */
	static class CountingSimHash implements PolicySimHash {
		private final PolicySimHash delegate;
		private final AtomicInteger invocations = new AtomicInteger();

		CountingSimHash(PolicySimHash delegate) {
			this.delegate = delegate;
		}

		int invocations() {
			return invocations.get();
		}

		@Override
		public long fingerprint(String normalizedText) {
			invocations.incrementAndGet();
			return delegate.fingerprint(normalizedText);
		}
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
