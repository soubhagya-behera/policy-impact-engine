package com.soubhagya.policyimpactengine.policy.application;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

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
 * Application-level orchestration for a single policy observation.
 *
 * <p>Connects the existing pipeline stages in a fixed order without
 * reimplementing any of them:
 *
 * <pre>
 * load policy → fetch → extract → normalize → hash → observe version → diff (NEW_VERSION only)
 * </pre>
 *
 * <p>No URL validation, SSRF logic, response-size handling, HTML parsing,
 * normalization, hashing, version comparison, or diff computation lives
 * here; each stage is owned by its injected abstraction. The diff engine
 * stays pure: this layer loads the two persisted version contents and
 * invokes it, and the engine never sees repositories or entities.
 *
 * <p>Diff behavior by outcome:
 * <ul>
 * <li>{@code FIRST_VERSION} — no previous version exists; the diff engine
 * is not called and the result carries no diff.</li>
 * <li>{@code UNCHANGED} — no new version was created; the diff engine is
 * not called and the result carries no diff.</li>
 * <li>{@code NEW_VERSION} — the normalized content of the previous
 * version (number N&nbsp;-&nbsp;1) is diffed against the normalized
 * content of the newly created version (number N); the result carries
 * the actual {@link PolicyDiffResult}.</li>
 * </ul>
 *
 * <p><b>Transaction boundary:</b> this method is deliberately NOT
 * {@code @Transactional}. The policy lookup runs in the repository's own
 * short read transaction, the external HTTP fetch then executes with no
 * database transaction held open, {@link PolicyVersionService} owns
 * its persistence transaction for the observe step, and the predecessor
 * read after a new version runs in its own short read transaction. A
 * single transaction spanning lookup → network → parse → hash → persist
 * → diff would hold a database connection across an unbounded external
 * call.
 *
 * <p><b>Diff-failure rule:</b> if version persistence succeeds but the
 * subsequent diff computation fails, the failure propagates to the caller
 * unchanged — the persisted version remains (the observation genuinely
 * happened; retrying with the same content converges to
 * {@code UNCHANGED}), and no fake empty diff or fake outcome is returned.
 * There is no recovery system in this slice.
 *
 * <p>Other failures propagate unchanged: missing policy surfaces
 * {@link NoSuchElementException} (the existing service/repository
 * not-found convention), fetch failures propagate the existing fetch
 * exception, and extraction/normalization/hashing failures propagate to
 * the caller. No generic exception hierarchy is introduced and no fake
 * outcome is returned for exceptions.
 */
@Service
public class PolicyObservationService {

	private final PolicyRepository policyRepository;
	private final PolicyFetcher fetcher;
	private final PolicyContentExtractor extractor;
	private final PolicyTextNormalizer normalizer;
	private final PolicyContentHasher hasher;
	private final PolicyVersionService versionService;
	private final PolicyVersionRepository versionRepository;
	private final PolicyDiffEngine diffEngine;

	public PolicyObservationService(
			PolicyRepository policyRepository,
			PolicyFetcher fetcher,
			PolicyContentExtractor extractor,
			PolicyTextNormalizer normalizer,
			PolicyContentHasher hasher,
			PolicyVersionService versionService,
			PolicyVersionRepository versionRepository,
			PolicyDiffEngine diffEngine) {
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
		if (versionService == null) {
			throw new IllegalArgumentException("PolicyVersionService must not be null");
		}
		if (versionRepository == null) {
			throw new IllegalArgumentException("PolicyVersionRepository must not be null");
		}
		if (diffEngine == null) {
			throw new IllegalArgumentException("PolicyDiffEngine must not be null");
		}
		this.policyRepository = policyRepository;
		this.fetcher = fetcher;
		this.extractor = extractor;
		this.normalizer = normalizer;
		this.hasher = hasher;
		this.versionService = versionService;
		this.versionRepository = versionRepository;
		this.diffEngine = diffEngine;
	}

	/**
	 * Observes the current live content of the given policy.
	 *
	 * @param policyId identifier of an already-registered policy
	 * @return scalar observation result with a diff only for
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

		PolicyVersionObservation observation = versionService.observe(policyId, normalized, hash);

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

		return new PolicyObservationResult(
				policyId,
				observation.outcome(),
				created.getVersionNumber(),
				created.getContentHash(),
				Optional.of(diff));
	}
}
