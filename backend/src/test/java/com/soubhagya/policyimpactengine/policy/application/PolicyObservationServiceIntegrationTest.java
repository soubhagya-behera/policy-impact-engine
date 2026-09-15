package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.diff.LineBasedPolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.JsoupPolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

/**
 * Phase 2H — end-to-end observation test over the real deterministic
 * pipeline: stub HTTP (no internet) → real extractor → real normalizer →
 * real hasher → real repositories and version service against
 * Testcontainers PostgreSQL.
 *
 * <p>Proves: first observation creates version 1; equivalent formatting
 * with identical normalized content reports UNCHANGED with no new row;
 * changed wording creates version 2 while version 1 stays immutable.
 */
@SpringBootTest
@Testcontainers
class PolicyObservationServiceIntegrationTest {

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
	private PolicyDiffEngine diffEngine;

	@Autowired
	private PolicyChangeRecordRepository changeRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void cleanDatabase() {
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void fullPipelineCreatesVersionsDeterministically() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));

		String firstHtml = "<html><body><h1>Privacy Policy</h1><p>We collect email addresses.</p></body></html>";
		// Same wording, formatting-only noise: extra whitespace plus a script
		// element the extractor strips, so normalized content is identical.
		String sameContentReformatted = "<html><head><script>var x = 1;</script></head><body>"
				+ "<h1>  Privacy   Policy </h1><p>We collect email addresses.</p></body></html>";
		String changedHtml = "<html><body><h1>Privacy Policy</h1>"
				+ "<p>We collect email addresses and location data.</p></body></html>";

		String firstNormalized = normalizer.normalize(extractor.extract(firstHtml));
		String reformattedNormalized = normalizer.normalize(extractor.extract(sameContentReformatted));
		assertThat(reformattedNormalized).isEqualTo(firstNormalized);

		StubPolicyFetcher stubFetcher = new StubPolicyFetcher(firstHtml);
		PolicyObservationPersistenceService persistenceService = new PolicyObservationPersistenceService(
				versionService, versionRepository, diffEngine, changeRepository, transactionManager);
		PolicyObservationService orchestrator = new PolicyObservationService(
				policyRepository, stubFetcher, extractor, normalizer, hasher, persistenceService);

		PolicyObservationResult first = orchestrator.observe(policy.getId());
		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(first.versionNumber()).isEqualTo(1);
		assertThat(first.contentHash()).isEqualTo(hasher.hash(firstNormalized));
		assertThat(versionRepository.count()).isEqualTo(1);

		PolicyVersion versionOne = versionRepository
				.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId()).orElseThrow();
		String versionOneContent = versionOne.getNormalizedContent();
		String versionOneHash = versionOne.getContentHash();
		Instant versionOneObservedAt = versionOne.getObservedAt();
		assertThat(versionOneContent).isEqualTo(firstNormalized);
		assertThat(versionOneHash).isEqualTo(hasher.hash(firstNormalized));

		stubFetcher.setHtml(sameContentReformatted);
		PolicyObservationResult second = orchestrator.observe(policy.getId());
		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(second.versionNumber()).isEqualTo(1);
		assertThat(second.contentHash()).isEqualTo(versionOneHash);
		assertThat(versionRepository.count()).isEqualTo(1);

		stubFetcher.setHtml(changedHtml);
		PolicyObservationResult third = orchestrator.observe(policy.getId());
		String changedNormalized = normalizer.normalize(extractor.extract(changedHtml));
		assertThat(third.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(third.versionNumber()).isEqualTo(2);
		assertThat(third.contentHash()).isEqualTo(hasher.hash(changedNormalized));

		List<PolicyVersion> versions = versionRepository
				.findByPolicy_IdOrderByVersionNumberAsc(policy.getId());
		assertThat(versions).hasSize(2);
		assertThat(versions).extracting(PolicyVersion::getVersionNumber).containsExactly(1, 2);

		PolicyVersion persistedOne = versions.get(0);
		assertThat(persistedOne.getNormalizedContent()).isEqualTo(versionOneContent);
		assertThat(persistedOne.getContentHash()).isEqualTo(versionOneHash);
		assertThat(persistedOne.getObservedAt()).isEqualTo(versionOneObservedAt);

		PolicyVersion persistedTwo = versions.get(1);
		assertThat(persistedTwo.getNormalizedContent()).isEqualTo(changedNormalized);
		assertThat(persistedTwo.getContentHash()).isEqualTo(hasher.hash(changedNormalized));
	}

	/**
	 * Deterministic in-memory fetcher: returns controlled HTML without
	 * touching the network. Mutable so one test can drive successive
	 * observations with different page content.
	 */
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
