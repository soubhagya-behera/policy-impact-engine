package com.soubhagya.policyimpactengine.policy.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.diff.PolicyChange;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;

/**
 * Phase 2K — owns the single persistence transaction for a policy
 * observation: version creation plus change-record persistence.
 *
 * <p><b>Transaction boundary.</b> {@link #store} is the only persistence
 * entry point of the observation path. The caller
 * ({@link PolicyObservationService}) performs the external HTTP fetch plus
 * extraction, normalization, and hashing with no database transaction held
 * open, then delegates here. This method then runs, in one short database
 * transaction with no network I/O (programmatic {@link TransactionTemplate}
 * rather than a proxy annotation, so the boundary holds however the service
 * is constructed):
 *
 * <pre>
 * observe version (joins this transaction) → load predecessor N-1 → pure diff → persist changes
 * </pre>
 *
 * <p>{@link PolicyVersionService#observe} uses {@code REQUIRED} propagation,
 * so when called from inside {@link #store} it joins this transaction rather
 * than committing separately. Version N and its change rows therefore commit
 * or roll back together: a change-persistence or diff failure rolls back the
 * version insert as well, so the database never holds a version whose
 * transition has no change records. The pure in-memory diff runs inside the
 * transaction, but it performs no I/O; the unbounded external fetch stays
 * outside, preserving the existing rule that HTTP fetching must not happen
 * inside a long-running database transaction.
 *
 * <p><b>Ordering.</b> Version N is created first, the previous version N-1
 * is identified, the diff is calculated, and the resulting records are
 * persisted in deterministic document order ({@code changeOrder} is the
 * zero-based diff index). {@code FIRST_VERSION} and {@code UNCHANGED} never
 * invoke the diff engine and persist no rows, and no artificial
 * "unchanged" change is ever stored.
 *
 * <p><b>Empty diff.</b> A {@code NEW_VERSION} outcome means the content hash
 * differed, so the diff is expected to be non-empty. If the engine somehow
 * returns an empty {@link PolicyDiffResult} for a new version, zero rows are
 * persisted — no change is invented — and the result still carries the
 * (empty) diff so the outcome stays explicit.
 *
 * <p><b>Failures.</b> Nothing is caught or retried here. A persistence
 * failure propagates to the caller, the transaction rolls back, and no
 * successful observation result is returned — the caller never receives a
 * result that falsely implies the complete transition was persisted.
 * Retrying the observation with the same content then re-attempts the whole
 * transition (no partial version row remains to skew the retry).
 */
@Service
public class PolicyObservationPersistenceService {

	private final PolicyVersionService versionService;
	private final PolicyVersionRepository versionRepository;
	private final PolicyDiffEngine diffEngine;
	private final PolicyChangeRecordRepository changeRepository;
	private final TransactionTemplate transactionTemplate;

	public PolicyObservationPersistenceService(
			PolicyVersionService versionService,
			PolicyVersionRepository versionRepository,
			PolicyDiffEngine diffEngine,
			PolicyChangeRecordRepository changeRepository,
			PlatformTransactionManager transactionManager) {
		if (versionService == null) {
			throw new IllegalArgumentException("PolicyVersionService must not be null");
		}
		if (versionRepository == null) {
			throw new IllegalArgumentException("PolicyVersionRepository must not be null");
		}
		if (diffEngine == null) {
			throw new IllegalArgumentException("PolicyDiffEngine must not be null");
		}
		if (changeRepository == null) {
			throw new IllegalArgumentException("PolicyChangeRecordRepository must not be null");
		}
		if (transactionManager == null) {
			throw new IllegalArgumentException("PlatformTransactionManager must not be null");
		}
		this.versionService = versionService;
		this.versionRepository = versionRepository;
		this.diffEngine = diffEngine;
		this.changeRepository = changeRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * Persists one observation of already-processed content.
	 *
	 * @param policyId identifier of an already-registered policy
	 * @param normalizedContent canonical normalized content; must not be null
	 * @param contentHash SHA-256 hash of the normalized content; must not be blank
	 * @return scalar observation result with a diff only for
	 *         {@code NEW_VERSION}; never exposes JPA entities
	 */
	public PolicyObservationResult store(UUID policyId, String normalizedContent, String contentHash) {
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		if (normalizedContent == null) {
			throw new IllegalArgumentException("Normalized content must not be null");
		}
		if (contentHash == null || contentHash.isBlank()) {
			throw new IllegalArgumentException("Content hash must not be blank");
		}

		return transactionTemplate.execute(status -> {
			PolicyVersionObservation observation = versionService.observe(policyId, normalizedContent,
					contentHash);

			if (observation.outcome() != PolicyVersionObservationOutcome.NEW_VERSION) {
				return new PolicyObservationResult(
						policyId,
						observation.outcome(),
						observation.version().getVersionNumber(),
						observation.version().getContentHash(),
						Optional.empty());
			}

			PolicyVersion created = observation.version();
			PolicyVersion previous = versionRepository
					.findByPolicy_IdAndVersionNumber(policyId, created.getVersionNumber() - 1)
					.orElseThrow(() -> new IllegalStateException(
							"Previous version " + (created.getVersionNumber() - 1)
									+ " of policy " + policyId + " not found for diffing"));

			PolicyDiffResult diff = diffEngine.diff(
					previous.getNormalizedContent(), created.getNormalizedContent());

			if (!diff.changes().isEmpty()) {
				List<PolicyChangeRecord> records = new ArrayList<>(diff.changes().size());
				int order = 0;
				for (PolicyChange change : diff.changes()) {
					records.add(new PolicyChangeRecord(
							previous,
							created,
							change.type(),
							change.oldText(),
							change.newText(),
							order++));
				}
				changeRepository.saveAll(records);
				changeRepository.flush();
			}

			return new PolicyObservationResult(
					policyId,
					observation.outcome(),
					created.getVersionNumber(),
					created.getContentHash(),
					Optional.of(diff));
		});
	}

}
