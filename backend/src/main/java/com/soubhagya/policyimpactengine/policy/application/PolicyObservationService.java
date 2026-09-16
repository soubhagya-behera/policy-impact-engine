package com.soubhagya.policyimpactengine.policy.application;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyTextNormalizer;

/**
 * Application-level orchestration for a single policy observation.
 *
 * <p>Connects the existing pipeline stages in a fixed order without
 * reimplementing any of them:
 *
 * <pre>
 * load policy → fetch → extract → normalize → hash → persist (version + changes)
 * </pre>
 *
 * <p>No URL validation, SSRF logic, response-size handling, HTML parsing,
 * normalization, hashing, version comparison, diff computation, change
 * persistence, or SimHash similarity lives here; each stage is owned by its
 * injected abstraction. Diff-to-version integration, change-record
 * persistence, and SimHash similarity for {@code NEW_VERSION} are owned by
 * {@link PolicyObservationPersistenceService}: for {@code NEW_VERSION} the
 * normalized content of the previous version (number N&nbsp;-&nbsp;1) is
 * diffed against the new version (number N), the resulting changes are
 * persisted in deterministic document order, and a numerical SimHash
 * similarity (previous/new fingerprints, Hamming distance, linear similarity
 * via {@link com.soubhagya.policyimpactengine.diff.SimHashDistance}) is
 * produced as an additional signal only. {@code FIRST_VERSION} and
 * {@code UNCHANGED} persist no change rows and carry no diff or similarity;
 * SHA-256 remains authoritative for unchanged detection and SimHash never
 * changes whether a version is created.
 *
 * <p><b>Transaction boundary:</b> this method is deliberately NOT
 * {@code @Transactional}. The policy lookup runs in the repository's own
 * short read transaction, the external HTTP fetch then executes with no
 * database transaction held open, and
 * {@link PolicyObservationPersistenceService#store} owns the single short
 * persistence transaction (version creation plus change persistence, with
 * the pure in-memory diff inside it and no network I/O, followed by the
 * pure SimHash similarity outside the transaction). A single transaction
 * spanning lookup → network → parse → hash → persist would hold a database
 * connection across an unbounded external call.
 *
 * <p><b>Failure rules:</b> persistence failures propagate unchanged through
 * the persistence service — the transaction rolls back version and changes
 * together, so no successful result is returned that would falsely imply
 * the complete transition was persisted. SimHash failures propagate
 * explicitly with no fake similarity (version and changes already committed
 * remain persisted). Other failures propagate unchanged as before: missing
 * policy surfaces {@link NoSuchElementException} (the existing
 * service/repository not-found convention), fetch failures propagate the
 * existing fetch exception, and extraction/normalization/hashing failures
 * propagate to the caller. No generic exception hierarchy is introduced and
 * no fake outcome is returned for exceptions.
 */
@Service
public class PolicyObservationService {

	private final PolicyRepository policyRepository;
	private final PolicyFetcher fetcher;
	private final PolicyContentExtractor extractor;
	private final PolicyTextNormalizer normalizer;
	private final PolicyContentHasher hasher;
	private final PolicyObservationPersistenceService persistenceService;

	public PolicyObservationService(
			PolicyRepository policyRepository,
			PolicyFetcher fetcher,
			PolicyContentExtractor extractor,
			PolicyTextNormalizer normalizer,
			PolicyContentHasher hasher,
			PolicyObservationPersistenceService persistenceService) {
		if (policyRepository == null) {
			throw new IllegalArgumentException("PolicyRepository must not be null");
		}
		if (fetcher == null) {
			throw new IllegalArgumentException("PolicyFetcher must not be null");
		}
		if (extractor == null) {
			throw new IllegalArgumentException("PolicyContentExtractor must not be null");
		}
		if (normalizer == null) {
			throw new IllegalArgumentException("PolicyTextNormalizer must not be null");
		}
		if (hasher == null) {
			throw new IllegalArgumentException("PolicyContentHasher must not be null");
		}
		if (persistenceService == null) {
			throw new IllegalArgumentException("PolicyObservationPersistenceService must not be null");
		}
		this.policyRepository = policyRepository;
		this.fetcher = fetcher;
		this.extractor = extractor;
		this.normalizer = normalizer;
		this.hasher = hasher;
		this.persistenceService = persistenceService;
	}

	/**
	 * Observes the current live content of the given policy.
	 *
	 * @param policyId identifier of an already-registered policy
	 * @return scalar observation result with a diff and similarity only for
	 *         {@code NEW_VERSION}; never exposes JPA entities
	 */
	public PolicyObservationResult observe(UUID policyId) {
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}

		Policy policy = policyRepository.findById(policyId)
				.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));

		FetchResult fetched = fetcher.fetch(policy.getUrl());
		String extracted = extractor.extract(fetched.body());
		String normalized = normalizer.normalize(extracted);
		String hash = hasher.hash(normalized);

		return persistenceService.store(policyId, normalized, hash);
	}
}
