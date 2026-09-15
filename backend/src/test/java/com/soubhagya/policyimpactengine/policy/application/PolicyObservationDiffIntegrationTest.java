package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.LineBasedPolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyChange;
import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
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
 * Phase 2J — end-to-end diff-to-version integration over the real
 * deterministic pipeline against Testcontainers PostgreSQL: stub HTTP (no
 * internet) → real extractor → real normalizer → real hasher → real
 * repositories, version service, and diff engine.
 *
 * <p>Proves: first observation yields FIRST_VERSION with no diff and
 * without invoking the diff engine; formatting-only changes yield
 * UNCHANGED with no diff and no diff invocation; wording changes yield
 * NEW_VERSION with a diff of the persisted Version 1 versus Version 2
 * contents while Version 1 stays immutable.
 */
@SpringBootTest
@Testcontainers
class PolicyObservationDiffIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private PolicyVersionService versionService;

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();
	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();
	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void cleanDatabase() {
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void observationIntegratesVersionsWithDiff() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));

		String firstHtml = "<html><body><h1>Privacy Policy</h1>"
				+ "<p>We collect your location data.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>We retain data for 30 days.</p></body></html>";
		// Formatting-only noise: script element plus extra whitespace the
		// extractor strips and the normalizer collapses, so the canonical
		// content is identical.
		String reformattedHtml = "<html><head><script>var x = 1;</script></head><body>"
				+ "<h1>  Privacy   Policy </h1>"
				+ "<p>We collect your location data.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>\n  We retain data for 30 days.  \n</p></body></html>";
		// Wording changes in the first and last paragraphs; the middle
		// paragraph is a stable anchor so each replacement diffs as one
		// MODIFIED change rather than separate add/remove pairs.
		String changedHtml = "<html><body><h1>Privacy Policy</h1>"
				+ "<p>We collect your precise location data.</p>"
				+ "<p>This policy applies to all users.</p>"
				+ "<p>We retain data for 90 days.</p></body></html>";

		String firstNormalized = normalizer.normalize(extractor.extract(firstHtml));
		assertThat(normalizer.normalize(extractor.extract(reformattedHtml))).isEqualTo(firstNormalized);
		String changedNormalized = normalizer.normalize(extractor.extract(changedHtml));
		assertThat(changedNormalized).isNotEqualTo(firstNormalized);

		StubPolicyFetcher stubFetcher = new StubPolicyFetcher(firstHtml);
		CountingDiffEngine countingDiff = new CountingDiffEngine(new LineBasedPolicyDiffEngine());
		PolicyObservationService orchestrator = new PolicyObservationService(
				policyRepository, stubFetcher, extractor, normalizer, hasher, versionService,
				versionRepository, countingDiff);

		PolicyObservationResult first = orchestrator.observe(policy.getId());
		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(first.versionNumber()).isEqualTo(1);
		assertThat(first.contentHash()).isEqualTo(hasher.hash(firstNormalized));
		assertThat(first.diff()).isEmpty();
		assertThat(countingDiff.invocations()).isZero();
		assertThat(versionRepository.count()).isEqualTo(1);

		PolicyVersion versionOne = versionRepository
				.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId()).orElseThrow();
		String versionOneContent = versionOne.getNormalizedContent();
		String versionOneHash = versionOne.getContentHash();
		Instant versionOneObservedAt = versionOne.getObservedAt();
		assertThat(versionOneContent).isEqualTo(firstNormalized);

		stubFetcher.setHtml(reformattedHtml);
		PolicyObservationResult second = orchestrator.observe(policy.getId());
		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(second.versionNumber()).isEqualTo(1);
		assertThat(second.contentHash()).isEqualTo(versionOneHash);
		assertThat(second.diff()).isEmpty();
		assertThat(countingDiff.invocations()).isZero();
		assertThat(versionRepository.count()).isEqualTo(1);

		stubFetcher.setHtml(changedHtml);
		PolicyObservationResult third = orchestrator.observe(policy.getId());
		assertThat(third.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(third.versionNumber()).isEqualTo(2);
		assertThat(third.contentHash()).isEqualTo(hasher.hash(changedNormalized));
		assertThat(countingDiff.invocations()).isEqualTo(1);
		assertThat(third.diff()).isPresent();
		assertThat(third.diff().orElseThrow().changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED,
						"We collect your location data.",
						"We collect your precise location data."),
				new PolicyChange(PolicyChangeType.MODIFIED,
						"We retain data for 30 days.",
						"We retain data for 90 days."));

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
	 * Real diff behavior with an invocation counter, so the test can prove
	 * the engine runs exactly when a new version is created — and never
	 * otherwise — without replacing it by a mock.
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
