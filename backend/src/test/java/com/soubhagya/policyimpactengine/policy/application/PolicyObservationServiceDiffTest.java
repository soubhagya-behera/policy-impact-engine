package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.diff.PolicyChange;
import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;

/**
 * Phase 2K — deterministic unit tests for version-plus-change persistence.
 *
 * <p>No Spring context, no database, no network. The version service,
 * version repository, diff engine, and change repository are Mockito mocks
 * with controlled answers; the diff algorithm itself is covered by its own
 * 2I tests and the repository behavior by Testcontainers tests.
 */
@ExtendWith(MockitoExtension.class)
class PolicyObservationServiceDiffTest {

	@Mock
	private PolicyVersionService versionService;

	@Mock
	private PolicyVersionRepository versionRepository;

	@Mock
	private PolicyDiffEngine diffEngine;

	@Mock
	private PolicyChangeRecordRepository changeRepository;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	@InjectMocks
	private PolicyObservationPersistenceService service;

	@BeforeEach
	void stubTransactionManager() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	@Test
	void firstVersionCarriesNoDiffAndPersistsNoChanges() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("first normalized"), eq("hash-first")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.FIRST_VERSION,
						new PolicyVersion(policy, 1, "first normalized", "hash-first")));

		PolicyObservationResult result = service.store(policyId, "first normalized", "hash-first");

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(result.versionNumber()).isEqualTo(1);
		assertThat(result.diff()).isEmpty();
	}

	@Test
	void firstVersionNeverLooksUpPreviousVersionOrDiffsOrPersists() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("first normalized"), eq("hash-first")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.FIRST_VERSION,
						new PolicyVersion(policy, 1, "first normalized", "hash-first")));

		service.store(policyId, "first normalized", "hash-first");

		verifyNoInteractions(versionRepository, diffEngine, changeRepository);
	}

	@Test
	void unchangedCarriesNoDiffAndPersistsNoChanges() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("same normalized"), eq("hash-same")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.UNCHANGED,
						new PolicyVersion(policy, 1, "same normalized", "hash-same")));

		PolicyObservationResult result = service.store(policyId, "same normalized", "hash-same");

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(result.versionNumber()).isEqualTo(1);
		assertThat(result.diff()).isEmpty();
	}

	@Test
	void unchangedNeverInvokesDiffEngineOrChangeRepository() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("same normalized"), eq("hash-same")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.UNCHANGED,
						new PolicyVersion(policy, 1, "same normalized", "hash-same")));

		service.store(policyId, "same normalized", "hash-same");

		verifyNoInteractions(versionRepository, diffEngine, changeRepository);
	}

	@Test
	void newVersionDiffsPreviousAgainstNewInOrderAndPersistsChangesInOrder() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		PolicyVersion previous = new PolicyVersion(policy, 1, "previous normalized", "hash-previous");
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(previous));
		PolicyDiffResult diff = new PolicyDiffResult(List.of(
				new PolicyChange(PolicyChangeType.MODIFIED, "previous normalized", "new normalized")));
		when(diffEngine.diff("previous normalized", "new normalized")).thenReturn(diff);

		PolicyObservationResult result = service.store(policyId, "new normalized", "hash-new");

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.versionNumber()).isEqualTo(2);
		assertThat(result.contentHash()).isEqualTo("hash-new");
		assertThat(result.diff()).contains(diff);

		InOrder order = inOrder(versionService, versionRepository, diffEngine, changeRepository);
		order.verify(versionService).observe(policyId, "new normalized", "hash-new");
		order.verify(versionRepository).findByPolicy_IdAndVersionNumber(policyId, 1);
		order.verify(diffEngine).diff("previous normalized", "new normalized");
		order.verify(changeRepository).saveAll(anyList());
		order.verify(changeRepository).flush();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<PolicyChangeRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
		verify(changeRepository).saveAll(recordsCaptor.capture());
		assertThat(recordsCaptor.getValue()).hasSize(1);
		assertThat(recordsCaptor.getValue().get(0).getChangeType())
				.isEqualTo(PolicyChangeType.MODIFIED);
		assertThat(recordsCaptor.getValue().get(0).getOldText()).isEqualTo("previous normalized");
		assertThat(recordsCaptor.getValue().get(0).getNewText()).isEqualTo("new normalized");
		assertThat(recordsCaptor.getValue().get(0).getChangeOrder()).isZero();
	}

	@Test
	void diffEngineReceivesNormalizedContentNotRawHtml() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("normalized text"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "normalized text", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(
						new PolicyVersion(policy, 1, "previous normalized", "hash-previous")));
		PolicyDiffResult diff = new PolicyDiffResult(List.of());
		when(diffEngine.diff(eq("previous normalized"), eq("normalized text"))).thenReturn(diff);

		service.store(policyId, "normalized text", "hash-new");

		ArgumentCaptor<String> oldCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> newCaptor = ArgumentCaptor.forClass(String.class);
		verify(diffEngine).diff(oldCaptor.capture(), newCaptor.capture());
		assertThat(oldCaptor.getValue()).isEqualTo("previous normalized");
		assertThat(newCaptor.getValue()).isEqualTo("normalized text");
		assertThat(oldCaptor.getValue()).doesNotContain("<html>");
		assertThat(newCaptor.getValue()).doesNotContain("<html>");
	}

	@Test
	void emptyDiffPersistsNoRowsButStillCarriesEmptyDiff() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(
						new PolicyVersion(policy, 1, "previous normalized", "hash-previous")));
		PolicyDiffResult emptyDiff = new PolicyDiffResult(List.of());
		when(diffEngine.diff("previous normalized", "new normalized")).thenReturn(emptyDiff);

		PolicyObservationResult result = service.store(policyId, "new normalized", "hash-new");

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.diff()).contains(emptyDiff);
		verifyNoInteractions(changeRepository);
	}

	@Test
	void changePersistenceFailurePropagatesWithoutSuccessfulResult() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(
						new PolicyVersion(policy, 1, "previous normalized", "hash-previous")));
		PolicyDiffResult diff = new PolicyDiffResult(List.of(
				new PolicyChange(PolicyChangeType.MODIFIED, "previous normalized", "new normalized")));
		when(diffEngine.diff("previous normalized", "new normalized")).thenReturn(diff);
		when(changeRepository.saveAll(anyList()))
				.thenThrow(new IllegalStateException("change persistence failed"));

		assertThatThrownBy(() -> service.store(policyId, "new normalized", "hash-new"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("change persistence failed");

		verify(versionService).observe(policyId, "new normalized", "hash-new");
	}

	@Test
	void diffFailurePropagatesWithoutFakeDiff() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(
						new PolicyVersion(policy, 1, "previous normalized", "hash-previous")));
		when(diffEngine.diff("previous normalized", "new normalized"))
				.thenThrow(new IllegalStateException("diff failed"));

		assertThatThrownBy(() -> service.store(policyId, "new normalized", "hash-new"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("diff failed");

		verify(versionService).observe(policyId, "new normalized", "hash-new");
		verifyNoInteractions(changeRepository);
	}

	@Test
	void missingPreviousVersionFailsLoudly() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.store(policyId, "new normalized", "hash-new"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not found for diffing");

		verifyNoInteractions(diffEngine, changeRepository);
	}

	private Policy registeredPolicy(String name, String url) {
		Policy policy = new Policy(name, url);
		policy.setId(UUID.randomUUID());
		return policy;
	}
}
