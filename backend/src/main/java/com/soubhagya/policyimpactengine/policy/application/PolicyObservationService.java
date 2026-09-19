package com.soubhagya.policyimpactengine.policy.application;

import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
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
 * load policy → begin attempt → fetch → extract → normalize → hash →
 * persist (version + changes) → complete attempt
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
 * short read transaction, the attempt row is begun in its own short write
 * transaction, the external HTTP fetch then executes with no database
 * transaction held open, and
 * {@link PolicyObservationPersistenceService#store} owns the single short
 * persistence transaction (version creation plus change persistence, with
 * the pure in-memory diff inside it and no network I/O, followed by the
 * pure SimHash similarity outside the transaction). The terminal attempt
 * update runs in its own short write transaction afterwards. A single
 * transaction spanning lookup → network → parse → hash → persist would
 * hold a database connection across an unbounded external call.
 *
 * <p><b>Attempt recording and claiming (Phase 2S/2U):</b> every
 * observation is recorded as a {@code PolicyFetchAttempt} and claimed
 * through the single shared {@link PolicyFetchAttemptService#beginAttempt}
 * path used by both the {@code MANUAL} entry point and the
 * {@code SCHEDULED} tick: a {@code PENDING} row is created and then
 * promoted with the conditional claim update inside one short
 * transaction, guarded by the V11 partial unique index. {@code FIRST_VERSION}
 * and {@code NEW_VERSION} complete as {@code SUCCESS}; {@code UNCHANGED}
 * completes as {@code SKIPPED_UNCHANGED}. Any fetch, extraction,
 * normalization, hashing, or persistence failure completes the attempt as
 * {@code FAILED} — in its own transaction, so a version/change rollback
 * still leaves the {@code FAILED} row behind — and then rethrows the
 * original exception unchanged. If beginning (claiming) the attempt itself
 * fails, the observation fails fast with no silent unrecorded path and no
 * fetch: in particular a lost claim surfaces
 * {@link com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchClaimRejectedException}
 * and performs no HTTP work, leaving no second runnable row.
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
	private final PolicyFetchAttemptService attemptService;

	public PolicyObservationService(
			PolicyRepository policyRepository,
			PolicyFetcher fetcher,
			PolicyContentExtractor extractor,
			PolicyTextNormalizer normalizer,
			PolicyContentHasher hasher,
			PolicyObservationPersistenceService persistenceService,
			PolicyFetchAttemptService attemptService) {
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
		if (attemptService == null) {
			throw new IllegalArgumentException("AttemptService must not be null");
		}
		this.policyRepository = policyRepository;
		this.fetcher = fetcher;
		this.extractor = extractor;
		this.normalizer = normalizer;
		this.hasher = hasher;
		this.persistenceService = persistenceService;
		this.attemptService = attemptService;
	}

	/**
	 * Observes the current live content of the given policy as a manual
	 * check.
	 *
	 * @param policyId identifier of an already-registered policy
	 * @return scalar observation result with a diff and similarity only for
	 *         {@code NEW_VERSION}; never exposes JPA entities
	 */
	public PolicyObservationResult observe(UUID policyId) {
		return observe(policyId, PolicyFetchAttemptTrigger.MANUAL);
	}

	/**
	 * Observes the current live content of the given policy with an
	 * explicit trigger ({@code MANUAL} for direct calls, {@code SCHEDULED}
	 * for the monitoring scheduler). Exactly one fetch → extract →
	 * normalize → hash → persist pipeline runs in either case; only the
	 * recorded attempt trigger differs.
	 *
	 * @param policyId identifier of an already-registered policy
	 * @param trigger what triggered this check; must not be null
	 * @return scalar observation result with a diff and similarity only for
	 *         {@code NEW_VERSION}; never exposes JPA entities
	 */
	public PolicyObservationResult observe(UUID policyId, PolicyFetchAttemptTrigger trigger) {
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		if (trigger == null) {
			throw new IllegalArgumentException("Trigger must not be null");
		}

		Policy policy = policyRepository.findById(policyId)
				.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));

		PolicyFetchAttempt attempt =
				attemptService.beginAttempt(policy, trigger);

		FetchResult fetched;
		try {
			fetched = fetcher.fetch(policy.getUrl());
		}
		catch (RuntimeException fetchFailure) {
			attemptService.markFailed(attempt.getId(), null, null, messageOf(fetchFailure));
			throw fetchFailure;
		}
		String extracted;
		String normalized;
		String hash;
		try {
			extracted = extractor.extract(fetched.body());
			normalized = normalizer.normalize(extracted);
			hash = hasher.hash(normalized);
		}
		catch (RuntimeException pipelineFailure) {
			attemptService.markFailed(attempt.getId(), fetched.statusCode(),
					byteLength(fetched.body()), messageOf(pipelineFailure));
			throw pipelineFailure;
		}
		PolicyObservationResult result;
		try {
			result = persistenceService.store(policyId, normalized, hash);
		}
		catch (RuntimeException persistenceFailure) {
			attemptService.markFailed(attempt.getId(), fetched.statusCode(),
					byteLength(fetched.body()), messageOf(persistenceFailure));
			throw persistenceFailure;
		}
		if (result.outcome() == PolicyVersionObservationOutcome.UNCHANGED) {
			attemptService.markSkippedUnchanged(attempt.getId(), fetched.statusCode(),
					byteLength(fetched.body()));
		}
		else {
			attemptService.markSucceeded(attempt.getId(), fetched.statusCode(),
					byteLength(fetched.body()));
		}
		return result;
	}

	/**
	 * Approximates the fetched response size as the UTF-8 byte length of
	 * the decoded body available to the orchestrator, or {@code null} when
	 * no response exists. Wire bytes may differ (framing, charset); exact
	 * accounting is deferred and must not reshape the fetcher in Phase 2S.
	 */
	private static Long byteLength(String body) {
		if (body == null) {
			return null;
		}
		return (long) body.getBytes(StandardCharsets.UTF_8).length;
	}

	private static String messageOf(RuntimeException failure) {
		if (failure.getMessage() != null) {
			return failure.getMessage();
		}
		return failure.getClass().getName();
	}
}
