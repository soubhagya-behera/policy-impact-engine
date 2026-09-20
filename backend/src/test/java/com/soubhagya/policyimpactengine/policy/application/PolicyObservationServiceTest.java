package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchClaimRejectedException;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
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

	@Mock
	private PolicyFetchAttemptService attemptService;

	@Mock
	private RetryPolicy retryPolicy;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	@InjectMocks
	private PolicyObservationService service;

	private void givenScheduleTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

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
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markSucceeded(any(), eq(200),
				eq(byteLength("<html><body><p>We collect email.</p></body></html>"))))
				.thenReturn(attempt);

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result).isEqualTo(stored);
		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(result.versionNumber()).isEqualTo(1);
		assertThat(result.contentHash()).isEqualTo("hash-1");
		verify(attemptService).beginAttempt(policy, PolicyFetchAttemptTrigger.MANUAL);
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
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markSkippedUnchanged(any(), eq(200), eq(byteLength("<html>same</html>"))))
				.thenReturn(attempt);

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(result.versionNumber()).isEqualTo(1);
		assertThat(result.contentHash()).isEqualTo("hash-same");
		verify(attemptService, never()).markSucceeded(any(), any(), any());
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
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markSucceeded(any(), eq(200), eq(byteLength("<html>changed</html>"))))
				.thenReturn(attempt);

		PolicyObservationResult result = service.observe(policy.getId());

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.versionNumber()).isEqualTo(2);
		assertThat(result.contentHash()).isEqualTo("hash-changed");
		assertThat(result.diff()).contains(diff);
		verify(attemptService, never()).markSkippedUnchanged(any(), any(), any());
	}

	@Test
	void missingPolicyPerformsNoFetch() {
		UUID id = UUID.randomUUID();
		when(policyRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.observe(id))
				.isInstanceOf(NoSuchElementException.class);

		verifyNoInteractions(fetcher, extractor, normalizer, hasher, persistenceService, attemptService);
	}

	@Test
	void fetchFailurePropagatesAndSkipsPersistence() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenThrow(new PolicyFetchException("connection refused"));
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markFailed(any(), isNull(), isNull(), eq("connection refused"),
				eq(PolicyFetchFailureKind.PERMANENT)))
				.thenReturn(attempt);
		givenScheduleTransaction();

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("connection refused");

		verifyNoInteractions(extractor, normalizer, hasher, persistenceService);
		verify(attemptService, never()).markSucceeded(any(), any(), any());
	}

	@Test
	void extractionFailurePropagatesAndSkipsPersistence() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenReturn(new FetchResult(policy.getUrl(), 200, "text/html", "<html>boom</html>"));
		when(extractor.extract("<html>boom</html>"))
				.thenThrow(new IllegalStateException("extraction failed"));
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markFailed(any(), eq(200), eq(byteLength("<html>boom</html>")),
				eq("extraction failed"), eq(PolicyFetchFailureKind.PERMANENT))).thenReturn(attempt);
		givenScheduleTransaction();

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
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markFailed(any(), eq(200), eq(byteLength("<html>x</html>")),
				eq("normalization failed"), eq(PolicyFetchFailureKind.PERMANENT))).thenReturn(attempt);
		givenScheduleTransaction();

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
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markFailed(any(), eq(200), eq(byteLength("<html>x</html>")),
				eq("hashing failed"), eq(PolicyFetchFailureKind.PERMANENT))).thenReturn(attempt);
		givenScheduleTransaction();

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
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markSucceeded(any(), eq(200), eq(byteLength("<html>raw</html>"))))
				.thenReturn(attempt);

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
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markSucceeded(any(), eq(200), eq(byteLength(html))))
				.thenReturn(attempt);

		service.observe(policy.getId());

		InOrder order = inOrder(attemptService, fetcher, extractor, normalizer, hasher,
				persistenceService, attemptService);
		order.verify(attemptService).beginAttempt(policy, PolicyFetchAttemptTrigger.MANUAL);
		order.verify(fetcher).fetch(policy.getUrl());
		order.verify(extractor).extract(html);
		order.verify(normalizer).normalize("extracted");
		order.verify(hasher).hash("normalized");
		order.verify(persistenceService).store(policy.getId(), "normalized", "hash-ordered");
		order.verify(attemptService).markSucceeded(any(), eq(200), eq(byteLength(html)));
	}

	@Test
	void persistenceFailureRecordsFailedAttemptAndRethrows() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		stubPipeline(policy, "<html>persist</html>", "extracted", "normalized", "hash-persist");
		when(persistenceService.store(eq(policy.getId()), eq("normalized"), eq("hash-persist")))
				.thenThrow(new IllegalStateException("version store down"));
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markFailed(any(), eq(200), eq(byteLength("<html>persist</html>")),
				eq("version store down"), eq(PolicyFetchFailureKind.TRANSIENT))).thenReturn(attempt);
		givenScheduleTransaction();

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("version store down");

		verify(attemptService, never()).markSucceeded(any(), any(), any());
		verify(attemptService, never()).markSkippedUnchanged(any(), any(), any());
	}

	@Test
	void beginAttemptFailureFailsFastWithoutFetch() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(attemptService.beginAttempt(eq(policy), eq(PolicyFetchAttemptTrigger.MANUAL)))
				.thenThrow(new IllegalStateException("attempt store down"));

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("attempt store down");

		verifyNoInteractions(fetcher, extractor, normalizer, hasher, persistenceService);
	}

	@Test
	void lostClaimIsRejectedWithoutFetchAndWithoutTerminalUpdate() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(attemptService.beginAttempt(eq(policy), eq(PolicyFetchAttemptTrigger.MANUAL)))
				.thenThrow(new PolicyFetchClaimRejectedException(policy.getId()));

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(PolicyFetchClaimRejectedException.class)
				.hasMessageContaining(policy.getId().toString());

		verifyNoInteractions(fetcher, extractor, normalizer, hasher, persistenceService);
		verify(attemptService, never()).markSucceeded(any(), any(), any());
		verify(attemptService, never()).markSkippedUnchanged(any(), any(), any());
		verify(attemptService, never()).markFailed(any(), any(), any(), any(), any());
	}

	@Test
	void transientFetchFailureSchedulesBackoffReschedulesAndRethrowsOriginal() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		Instant failedAt = Instant.parse("2026-09-18T10:05:00Z");
		Instant backoffAt = Instant.parse("2026-09-18T10:07:30Z");
		PolicyFetchException transientFailure =
				new PolicyFetchException("connection reset", null, new IOException("reset"), true);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl())).thenThrow(transientFailure);
		PolicyFetchAttempt failed = failedAttempt(policy,
				PolicyFetchFailureKind.TRANSIENT, 1, failedAt);
		when(attemptService.beginAttempt(eq(policy), eq(PolicyFetchAttemptTrigger.MANUAL)))
				.thenReturn(failed);
		when(attemptService.markFailed(any(), isNull(), isNull(), eq("connection reset"),
				eq(PolicyFetchFailureKind.TRANSIENT))).thenReturn(failed);
		when(retryPolicy.nextCheckAt(eq(failedAt), eq(PolicyFetchFailureKind.TRANSIENT), eq(1)))
				.thenReturn(backoffAt);
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		givenScheduleTransaction();

		Throwable thrown = catchThrowable(() -> service.observe(policy.getId()));

		assertThat(thrown).isSameAs(transientFailure);
		ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
		verify(policyRepository).save(captor.capture());
		assertThat(captor.getValue().getNextCheckAt()).isEqualTo(backoffAt);
		verify(fetcher, times(1)).fetch(policy.getUrl());
	}

	@Test
	void permanentFetchFailureCarriesStatusAndSchedulesInterval() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		Instant failedAt = Instant.parse("2026-09-18T10:05:00Z");
		Instant intervalAt = Instant.parse("2026-09-19T10:05:00Z");
		PolicyFetchException permanentFailure = new PolicyFetchException("gone", 404, false);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl())).thenThrow(permanentFailure);
		PolicyFetchAttempt failed = failedAttempt(policy,
				PolicyFetchFailureKind.PERMANENT, 1, failedAt);
		when(attemptService.beginAttempt(eq(policy), eq(PolicyFetchAttemptTrigger.MANUAL)))
				.thenReturn(failed);
		when(attemptService.markFailed(any(), eq(404), isNull(), eq("gone"),
				eq(PolicyFetchFailureKind.PERMANENT))).thenReturn(failed);
		when(retryPolicy.nextCheckAt(eq(failedAt), eq(PolicyFetchFailureKind.PERMANENT), eq(1)))
				.thenReturn(intervalAt);
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		givenScheduleTransaction();

		Throwable thrown = catchThrowable(() -> service.observe(policy.getId()));

		assertThat(thrown).isSameAs(permanentFailure);
		ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
		verify(policyRepository).save(captor.capture());
		assertThat(captor.getValue().getNextCheckAt()).isEqualTo(intervalAt);
		verify(fetcher, times(1)).fetch(policy.getUrl());
	}

	@Test
	void persistenceFailureSchedulesAsTransient() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		Instant failedAt = Instant.parse("2026-09-18T10:05:00Z");
		Instant backoffAt = Instant.parse("2026-09-18T10:12:00Z");
		stubPipeline(policy, "<html>persist</html>", "extracted", "normalized", "hash-persist");
		when(persistenceService.store(eq(policy.getId()), eq("normalized"), eq("hash-persist")))
				.thenThrow(new IllegalStateException("version store down"));
		PolicyFetchAttempt failed = failedAttempt(policy,
				PolicyFetchFailureKind.TRANSIENT, 2, failedAt);
		when(attemptService.beginAttempt(eq(policy), eq(PolicyFetchAttemptTrigger.MANUAL)))
				.thenReturn(failed);
		when(attemptService.markFailed(any(), eq(200), eq(byteLength("<html>persist</html>")),
				eq("version store down"), eq(PolicyFetchFailureKind.TRANSIENT))).thenReturn(failed);
		when(retryPolicy.nextCheckAt(eq(failedAt), eq(PolicyFetchFailureKind.TRANSIENT), eq(2)))
				.thenReturn(backoffAt);
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		givenScheduleTransaction();

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("version store down");

		ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
		verify(policyRepository).save(captor.capture());
		assertThat(captor.getValue().getNextCheckAt()).isEqualTo(backoffAt);
	}

	@Test
	void nullFailureMessageFallsBackToExceptionType() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(fetcher.fetch(policy.getUrl()))
				.thenReturn(new FetchResult(policy.getUrl(), 200, "text/html", "<html>x</html>"));
		when(extractor.extract("<html>x</html>")).thenReturn("extracted");
		when(normalizer.normalize("extracted")).thenReturn("normalized");
		when(hasher.hash("normalized")).thenThrow(new IllegalStateException());
		PolicyFetchAttempt attempt = startedAttempt(policy);
		when(attemptService.markFailed(any(), eq(200), eq(byteLength("<html>x</html>")),
				eq("java.lang.IllegalStateException"), eq(PolicyFetchFailureKind.PERMANENT)))
				.thenReturn(attempt);
		givenScheduleTransaction();

		assertThatThrownBy(() -> service.observe(policy.getId()))
				.isInstanceOf(IllegalStateException.class);
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

	private PolicyFetchAttempt startedAttempt(Policy policy) {
		PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, Instant.parse("2026-09-18T10:00:00Z"));
		when(attemptService.beginAttempt(eq(policy), eq(PolicyFetchAttemptTrigger.MANUAL)))
				.thenReturn(attempt);
		return attempt;
	}

	private PolicyFetchAttempt failedAttempt(Policy policy, PolicyFetchFailureKind kind,
			int attemptNumber, Instant completedAt) {
		PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, attemptNumber, completedAt.minusSeconds(1));
		attempt.complete(PolicyFetchAttemptStatus.FAILED, null, null, "down", kind, completedAt);
		return attempt;
	}

	private static Long byteLength(String body) {
		return (long) body.getBytes(StandardCharsets.UTF_8).length;
	}
}
