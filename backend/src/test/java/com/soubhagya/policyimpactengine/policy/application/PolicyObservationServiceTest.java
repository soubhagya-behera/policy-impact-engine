package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetchException;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;

/**
 * Phase 2H/2K — deterministic unit tests for the observation orchestrator.
 *
 * <p>No Spring context, no database, no network. The orchestrator runs the
 * fetch pipeline (repository, fetcher, extractor, normalizer, hasher) with
 * no database transaction held, then delegates persistence (version plus
 * change records, with the pure diff inside the persistence transaction) to
 * {@link PolicyObservationPersistenceService}. Diff and change persistence
 * behavior itself is covered by the persistence-service tests.
 */
@ExtendWith(MockitoExtension.class)
class PolicyObservationServiceTest {

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
	private PolicyObservationPersistenceService persistenceService;

	@InjectMocks
	private PolicyObservationService service;

	@Test
	void firstObservationRunsFullPipelineAndReturnsFirstVersion() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html><body><p>We collect email.</p></body></html>",
				"We collect email.", "We collect email.", "hash-1");
		PolicyObservationResult stored = new PolicyObservationResult(
				policy.getId(), PolicyVersionObservationOutcome.FIRST_VERSION, 1, "hash-1", Optional.empty(),
				Optional.empty());
		when(persistenceService.store(eq(policy.getId()), eq("We collect email."), eq("hash-1")))
				.thenReturn(stored);

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result).isEqualTo(stored);
		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(result.versionNumber()).isEqualTo(1);
		assertThat(result.contentHash()).isEqualTo("hash-1");
	}

	@Test
	void sameContentReturnsUnchanged() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>same</html>", "same text", "same text", "hash-same");
		PolicyObservationResult stored = new PolicyObservationResult(
				policy.getId(), PolicyVersionObservationOutcome.UNCHANGED, 1, "hash-same", Optional.empty(),
				Optional.empty());
		when(persistenceService.store(eq(policy.getId()), eq("same text"), eq("hash-same")))
				.thenReturn(stored);

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(result.versionNumber()).isEqualTo(1);
		assertThat(result.contentHash()).isEqualTo("hash-same");
	}

	@Test
	void changedContentReturnsNewVersion() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>changed</html>", "changed text", "changed text", "hash-changed");
		PolicyDiffResult diff = new PolicyDiffResult(java.util.List.of());
		PolicyObservationResult stored = new PolicyObservationResult(
				policy.getId(), PolicyVersionObservationOutcome.NEW_VERSION, 2, "hash-changed",
				Optional.of(diff), Optional.empty());
		when(persistenceService.store(eq(policy.getId()), eq("changed text"), eq("hash-changed")))
				.thenReturn(stored);

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.versionNumber()).isEqualTo(2);
		assertThat(result.contentHash()).isEqualTo("hash-changed");
		assertThat(result.diff()).contains(diff);
	}

	@Test
	void missingPolicyPerformsNoFetch() {
		UUID id = UUID.randomUUID();
		when(policyRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.observe(id))
				.isInstanceOf(NoSuchElementException.class);

		verifyNoInteractions(fetcher, extractor, normalizer, hasher, persistenceService);
	}

	@Test
	void fetchFailurePropagatesAndSkipsPersistence() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenThrow(new PolicyFetchException("connection refused"));

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("connection refused");

		verifyNoInteractions(extractor, normalizer, hasher, persistenceService);
	}

	@Test
	void extractionFailurePropagatesAndSkipsPersistence() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenReturn(new FetchResult(policy.getUrl(), 200, "text/html", "<html>boom</html>"));
		when(extractor.extract("<html>boom</html>"))
				.thenThrow(new IllegalStateException("extraction failed"));

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("extraction failed");

		verifyNoInteractions(persistenceService);
		verify(normalizer, never()).normalize(any());
		verify(hasher, never()).hash(any());
	}

	@Test
	void normalizationFailurePropagatesAndSkipsHasherAndPersistence() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenReturn(new FetchResult(policy.getUrl(), 200, "text/html", "<html>x</html>"));
		when(extractor.extract("<html>x</html>")).thenReturn("extracted");
		when(normalizer.normalize("extracted"))
				.thenThrow(new IllegalStateException("normalization failed"));

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("normalization failed");

		verify(hasher, never()).hash(any());
		verifyNoInteractions(persistenceService);
	}

	@Test
	void hashingFailurePropagatesAndSkipsPersistence() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenReturn(new FetchResult(policy.getUrl(), 200, "text/html", "<html>x</html>"));
		when(extractor.extract("<html>x</html>")).thenReturn("extracted");
		when(normalizer.normalize("extracted")).thenReturn("normalized");
		when(hasher.hash("normalized"))
				.thenThrow(new IllegalStateException("hashing failed"));

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("hashing failed");

		verifyNoInteractions(persistenceService);
	}

	@Test
	void suppliesExactNormalizedContentAndHashToPersistenceService() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>raw</html>", "extracted text", "normalized text", "hash-exact");
		when(persistenceService.store(eq(policy.getId()), eq("normalized text"), eq("hash-exact")))
				.thenReturn(new PolicyObservationResult(
						policy.getId(), PolicyVersionObservationOutcome.FIRST_VERSION, 1, "hash-exact",
						Optional.empty(), Optional.empty()));

		service.observe(policy.getId());

		ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
		verify(persistenceService).store(eq(policy.getId()), contentCaptor.capture(), hashCaptor.capture());
		assertThat(contentCaptor.getValue()).isEqualTo("normalized text");
		assertThat(hashCaptor.getValue()).isEqualTo("hash-exact");
	}

	@Test
	void invokesComponentsInPipelineOrder() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		String html = "<html>ordered</html>";
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenReturn(new FetchResult(policy.getUrl(), 200, "text/html", html));
		when(extractor.extract(html)).thenReturn("extracted");
		when(normalizer.normalize("extracted")).thenReturn("normalized");
		when(hasher.hash("normalized")).thenReturn("hash-ordered");
		when(persistenceService.store(policy.getId(), "normalized", "hash-ordered"))
				.thenReturn(new PolicyObservationResult(
						policy.getId(), PolicyVersionObservationOutcome.FIRST_VERSION, 1, "hash-ordered",
						Optional.empty(), Optional.empty()));

		service.observe(policy.getId());

		InOrder order = inOrder(fetcher, extractor, normalizer, hasher, persistenceService);
		order.verify(fetcher).fetch(policy.getUrl());
		order.verify(extractor).extract(html);
		order.verify(normalizer).normalize("extracted");
		order.verify(hasher).hash("normalized");
		order.verify(persistenceService).store(policy.getId(), "normalized", "hash-ordered");
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
