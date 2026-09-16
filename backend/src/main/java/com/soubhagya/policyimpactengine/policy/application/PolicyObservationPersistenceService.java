package com.soubhagya.policyimpactengine.policy.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.diff.PolicyChange;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.PolicySimHash;
import com.soubhagya.policyimpactengine.diff.SimHashDistance;
import com.soubhagya.policyimpactengine.diff.SimHashSimilarity;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;

/**
 * Phase 2K/2M — owns the single persistence transaction for a policy
 * observation: version creation plus change-record persistence, plus the
 * SimHash similarity signal for {@code NEW_VERSION}.
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
 *
 * <p><b>Phase 2M — SimHash similarity.</b> For {@code NEW_VERSION} only, a
 * numerical similarity signal is produced from the persisted/new normalized
 * canonical contents via {@link PolicySimHash} and {@link SimHashDistance}.
 * SHA-256 remains authoritative for unchanged detection: same SHA-256 → no
 * version, no diff, no SimHash; different SHA-256 → new version, diff, and
 * similarity. SimHash is an additional signal only; it never changes whether
 * a version is created and never converts a changed hash into
 * {@code UNCHANGED}. The pure, inexpensive SimHash calculation runs
 * <i>outside</i> the version-plus-changes transaction, after it commits, so a
 * SimHash failure propagates explicitly with no fake similarity and without
 * rolling back the already-committed version and change rows (retry then
 * re-attempts SimHash on the same persisted transition). {@code FIRST_VERSION}
 * and {@code UNCHANGED} never invoke SimHash and carry no similarity. For
 * {@code NEW_VERSION} the canonical normalized contents of the persisted
 * predecessor (N-1) and the new version (N) are fingerprinted, the Hamming
 * distance and the linear similarity {@code 1 - distance/64} are derived via
 * the existing {@link SimHashDistance} API, and no threshold or
 * classification is applied. SimHash never receives raw HTML, only the
 * normalized canonical texts.
 */
@Service
public class PolicyObservationPersistenceService {

	private final PolicyVersionService versionService;
	private final PolicyVersionRepository versionRepository;
	private final PolicyDiffEngine diffEngine;
	private final PolicyChangeRecordRepository changeRepository;
	private final PolicySimHash simHash;
	private final TransactionTemplate transactionTemplate;

	public PolicyObservationPersistenceService(
			PolicyVersionService versionService,
			PolicyVersionRepository versionRepository,
			PolicyDiffEngine diffEngine,
			PolicyChangeRecordRepository changeRepository,
			PolicySimHash simHash,
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
		if (simHash == null) {
			throw new IllegalArgumentException("PolicySimHash must not be null");
		}
		if (transactionManager == null) {
			throw new IllegalArgumentException("PlatformTransactionManager must not be null");
		}
		this.versionService = versionService;
		this.versionRepository = versionRepository;
		this.diffEngine = diffEngine;
		this.changeRepository = changeRepository;
		this.simHash = simHash;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * Persists one observation of already-processed content.
	 *
	 * @param policyId identifier of an already-registered policy
	 * @param normalizedContent canonical normalized content; must not be null
	 * @param contentHash SHA-256 hash of the normalized content; must not be blank
	 * @return scalar observation result with a diff only for
	 *         {@code NEW_VERSION} and a SimHash similarity only for
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

		AtomicReference<String> previousNormalizedRef = new AtomicReference<>();
		AtomicReference<String> newNormalizedRef = new AtomicReference<>();

		PolicyObservationResult intermediate = transactionTemplate.execute(status -> {
			PolicyVersionObservation observation = versionService.observe(policyId, normalizedContent,
					contentHash);

			if (observation.outcome() != PolicyVersionObservationOutcome.NEW_VERSION) {
				return new PolicyObservationResult(
						policyId,
						observation.outcome(),
						observation.version().getVersionNumber(),
						observation.version().getContentHash(),
						Optional.empty(),
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

			previousNormalizedRef.set(previous.getNormalizedContent());
			newNormalizedRef.set(created.getNormalizedContent());

			return new PolicyObservationResult(
					policyId,
					observation.outcome(),
					created.getVersionNumber(),
					created.getContentHash(),
					Optional.of(diff),
					Optional.empty());
		});

		if (intermediate == null) {
			throw new IllegalStateException("Transaction returned null observation result");
		}
		if (intermediate.outcome() != PolicyVersionObservationOutcome.NEW_VERSION) {
			return intermediate;
		}

		// NEW_VERSION: compute SimHash similarity outside the persistence
		// transaction so a SimHash failure does not roll back the already
		// committed version plus change rows, and so the short transaction
		// is not extended by this pure inexpensive calculation. The result
		// propagates explicitly — no fake similarity is returned.
		String previousNormalized = previousNormalizedRef.get();
		String newNormalized = newNormalizedRef.get();
		if (previousNormalized == null || newNormalized == null) {
			throw new IllegalStateException("Missing normalized content for SimHash similarity");
		}
		long previousHash = simHash.fingerprint(previousNormalized);
		long newHash = simHash.fingerprint(newNormalized);
		int hammingDistance = SimHashDistance.hammingDistance(previousHash, newHash);
		double similarity = SimHashDistance.similarity(previousHash, newHash);
		SimHashSimilarity simHashSimilarity = new SimHashSimilarity(
				previousHash, newHash, hammingDistance, similarity);

		return new PolicyObservationResult(
				intermediate.policyId(),
				intermediate.outcome(),
				intermediate.versionNumber(),
				intermediate.contentHash(),
				intermediate.diff(),
				Optional.of(simHashSimilarity));
	}

}
