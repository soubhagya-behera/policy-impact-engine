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
 * load policy → fetch → extract → normalize → hash → observe version
 * </pre>
 *
 * <p>No URL validation, SSRF logic, response-size handling, HTML parsing,
 * normalization, hashing, or version comparison lives here; each stage is
 * owned by its injected abstraction.
 *
 * <p><b>Transaction boundary:</b> this method is deliberately NOT
 * {@code @Transactional}. The policy lookup runs in the repository's own
 * short read transaction, the external HTTP fetch then executes with no
 * database transaction held open, and {@link PolicyVersionService} owns
 * its persistence transaction for the final observe step. A single
 * transaction spanning lookup → network → parse → hash → persist would
 * hold a database connection across an unbounded external call.
 *
 * <p>Failures propagate unchanged: missing policy surfaces
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

	public PolicyObservationService(
			PolicyRepository policyRepository,
			PolicyFetcher fetcher,
			PolicyContentExtractor extractor,
			PolicyTextNormalizer normalizer,
			PolicyContentHasher hasher,
			PolicyVersionService versionService) {
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
		this.policyRepository = policyRepository;
		this.fetcher = fetcher;
		this.extractor = extractor;
		this.normalizer = normalizer;
		this.hasher = hasher;
		this.versionService = versionService;
	}

	/**
	 * Observes the current live content of the given policy.
	 *
	 * @param policyId identifier of an already-registered policy
	 * @return scalar observation result; never exposes JPA entities
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

		return new PolicyObservationResult(
				policyId,
				observation.outcome(),
				observation.version().getVersionNumber(),
				observation.version().getContentHash());
	}
}
