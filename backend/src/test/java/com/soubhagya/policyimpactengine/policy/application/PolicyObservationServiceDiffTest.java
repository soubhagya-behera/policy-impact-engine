package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soubhagya.policyimpactengine.diff.PolicyChange;
import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;

/**
 * Phase 2J — deterministic unit tests for diff-to-version integration.
 *
 * <p>No Spring context, no database, no network. The version service,
 * version repository, and diff engine are Mockito mocks with controlled
 * answers; the diff algorithm itself is covered by its own 2I tests.
 */
@ExtendWith(MockitoExtension.class)
class PolicyObservationServiceDiffTest {

	@Mock
	private PolicyRepository policyRepository;

	@Mock
	private PolicyFetcher fetcher;

	@Mock
	private PolicyContentExtractor extractor;

	@Mock
	private PolicyTextNormalizer normalizer;

	@Mock
	private PolicyContentHasher hasher;

	@Mock
	private PolicyVersionService versionService;

	@Mock
	private PolicyVersionRepository versionRepository;

	@Mock
	private PolicyDiffEngine diffEngine;

	@InjectMocks
	private PolicyObservationService service;

	@Test
	void firstVersionCarriesNoDiff() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>first</html>", "extracted", "first normalized", "hash-first");
		when(versionService.observe(eq(policy.getId()), eq("first normalized"), eq("hash-first")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.FIRST_VERSION,
						new PolicyVersion(policy, 1, "first normalized", "hash-first")));

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(result.versionNumber()).isEqualTo(1);
		assertThat(result.diff()).isEmpty();
	}

	@Test
	void firstVersionNeverLooksUpPreviousVersionOrDiffs() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>first</html>", "extracted", "first normalized", "hash-first");
		when(versionService.observe(eq(policy.getId()), eq("first normalized"), eq("hash-first")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.FIRST_VERSION,
						new PolicyVersion(policy, 1, "first normalized", "hash-first")));

		service.observe(policy.getId());

		verifyNoInteractions(versionRepository, diffEngine);
	}

	@Test
	void unchangedCarriesNoDiff() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>same</html>", "extracted", "same normalized", "hash-same");
		when(versionService.observe(eq(policy.getId()), eq("same normalized"), eq("hash-same")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.UNCHANGED,
						new PolicyVersion(policy, 1, "same normalized", "hash-same")));

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(result.versionNumber()).isEqualTo(1);
		assertThat(result.diff()).isEmpty();
	}

	@Test
	void unchangedNeverInvokesDiffEngine() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>same</html>", "extracted", "same normalized", "hash-same");
		when(versionService.observe(eq(policy.getId()), eq("same normalized"), eq("hash-same")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.UNCHANGED,
						new PolicyVersion(policy, 1, "same normalized", "hash-same")));

		service.observe(policy.getId());

		verifyNoInteractions(versionRepository, diffEngine);
	}

	@Test
	void newVersionDiffsPreviousAgainstNewInOrder() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>new</html>", "extracted new", "new normalized", "hash-new");
		when(versionService.observe(eq(policy.getId()), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1))
				.thenReturn(Optional.of(
						new PolicyVersion(policy, 1, "previous normalized", "hash-previous")));
		PolicyDiffResult diff = new PolicyDiffResult(List.of(
				new PolicyChange(PolicyChangeType.MODIFIED, "previous normalized", "new normalized")));
		when(diffEngine.diff("previous normalized", "new normalized")).thenReturn(diff);

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.versionNumber()).isEqualTo(2);
		assertThat(result.contentHash()).isEqualTo("hash-new");
		assertThat(result.diff()).contains(diff);

		InOrder order = inOrder(versionService, versionRepository, diffEngine);
		order.verify(versionService).observe(policy.getId(), "new normalized", "hash-new");
		order.verify(versionRepository).findByPolicy_IdAndVersionNumber(policy.getId(), 1);
		order.verify(diffEngine).diff("previous normalized", "new normalized");
	}

	@Test
	void diffEngineReceivesNormalizedContentNotRawHtml() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenReturn(new FetchResult(policy.getUrl(), 200, "text/html", "<html><p>raw</p></html>"));
		when(extractor.extract("<html><p>raw</p></html>")).thenReturn("extracted <b>raw</b>");
		when(normalizer.normalize("extracted <b>raw</b>")).thenReturn("normalized text");
		when(hasher.hash("normalized text")).thenReturn("hash-new");
		when(versionService.observe(eq(policy.getId()), eq("normalized text"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "normalized text", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1))
				.thenReturn(Optional.of(
						new PolicyVersion(policy, 1, "previous normalized", "hash-previous")));
		PolicyDiffResult diff = new PolicyDiffResult(List.of());
		when(diffEngine.diff(eq("previous normalized"), eq("normalized text"))).thenReturn(diff);

		service.observe(policy.getId());

		ArgumentCaptor<String> oldCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> newCaptor = ArgumentCaptor.forClass(String.class);
		verify(diffEngine).diff(oldCaptor.capture(), newCaptor.capture());
		assertThat(oldCaptor.getValue()).isEqualTo("previous normalized");
		assertThat(newCaptor.getValue()).isEqualTo("normalized text");
		assertThat(oldCaptor.getValue()).doesNotContain("<html>");
		assertThat(newCaptor.getValue()).doesNotContain("<html>");
	}

	@Test
	void diffFailurePropagatesWithoutFakeDiff() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>new</html>", "extracted new", "new normalized", "hash-new");
		when(versionService.observe(eq(policy.getId()), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1))
				.thenReturn(Optional.of(
						new PolicyVersion(policy, 1, "previous normalized", "hash-previous")));
		when(diffEngine.diff("previous normalized", "new normalized"))
				.thenThrow(new IllegalStateException("diff failed"));

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("diff failed");

		verify(versionService).observe(policy.getId(), "new normalized", "hash-new");
	}

	@Test
	void missingPreviousVersionFailsLoudly() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>new</html>", "extracted new", "new normalized", "hash-new");
		when(versionService.observe(eq(policy.getId()), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 1))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not found for diffing");

		verifyNoInteractions(diffEngine);
	}

	private void stubPipeline(Policy policy, String html, String extracted, String normalized, String hash) {
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenReturn(new FetchResult(policy.getUrl(), 200, "text/html", html));
		when(extractor.extract(html)).thenReturn(extracted);
		when(normalizer.normalize(extracted)).thenReturn(normalized);
		when(hasher.hash(normalized)).thenReturn(hash);
	}

	private Policy registeredPolicy(String name, String url) {
		Policy policy = new Policy(name, url);
		policy.setId(UUID.randomUUID());
		return policy;
	}
}
