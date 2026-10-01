package com.soubhagya.policyimpactengine.policy.application;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.notification.application.NotificationFanOutService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyCheckResponse;

/**
 * Phase 16-B/2 — application facade for the synchronous manual policy
 * check (see DECISIONS.md ADR-034).
 *
 * <p>Flow per call: owner-scoped load (unknown/foreign behaves as
 * not-found) → {@code ARCHIVED} guard (refused before any claim or
 * fetch, with no reactivation side effect) → the existing
 * {@link PolicyObservationService#observe(UUID,
 * PolicyFetchAttemptTrigger) MANUAL} path (one claimed attempt, at most
 * one fetch, existing terminal/failure/reschedule behavior) → the
 * existing {@link NotificationFanOutService#fanOut} completion path →
 * read-model mapping. No pipeline stage is duplicated here and no
 * scheduler interaction exists.
 *
 * <p>The narrow {@code policy.application → notification.application}
 * fan-out edge mirrors the scheduler's own call (which lives in
 * monitoring): the observation service itself is never modified, and
 * fan-out opens no encompassing transaction. A fan-out failure is
 * isolated exactly like the scheduler isolates it per policy — the
 * successful observation stands and the endpoint still returns its
 * result, with the same healable gap (a later successful check heals
 * idempotently). Identity arrives only as an explicit UUID; this
 * service never touches the security context.
 */
@Service
public class PolicyCheckService {

	private final PolicyRepository policyRepository;
	private final PolicyObservationService observationService;
	private final NotificationFanOutService fanOutService;

	public PolicyCheckService(PolicyRepository policyRepository,
			PolicyObservationService observationService,
			NotificationFanOutService fanOutService) {
		if (policyRepository == null) {
			throw new IllegalArgumentException("PolicyRepository must not be null");
		}
		if (observationService == null) {
			throw new IllegalArgumentException("ObservationService must not be null");
		}
		if (fanOutService == null) {
			throw new IllegalArgumentException("FanOutService must not be null");
		}
		this.policyRepository = policyRepository;
		this.observationService = observationService;
		this.fanOutService = fanOutService;
	}

	/**
	 * Runs one synchronous manual check of the user's policy and maps
	 * the terminal observation result to the check response.
	 *
	 * @param userId identifier of the owning user, from the
	 *        authenticated principal (never from request data)
	 * @param policyId identifier of the policy to check
	 * @return read-model check response; never exposes JPA entities
	 * @throws NoSuchElementException when the policy is unknown or
	 *         owned by another user
	 * @throws PolicyArchivedException when the owned policy is archived
	 */
	public PolicyCheckResponse check(UUID userId, UUID policyId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		Policy policy = policyRepository.findByIdAndOwner_Id(policyId, userId)
				.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));
		if (policy.getStatus() == PolicyStatus.ARCHIVED) {
			throw new PolicyArchivedException(policyId);
		}
		PolicyObservationResult result = observationService.observe(policyId,
				PolicyFetchAttemptTrigger.MANUAL);
		try {
			fanOutService.fanOut(policyId, result);
		}
		catch (RuntimeException fanOutFailure) {
			// Isolated exactly like the scheduler isolates fan-out per
			// policy: the successful observation (attempt row, versions,
			// scheduling) stands untouched and the missing personalized
			// fan-out heals idempotently on a later successful check.
		}
		return PolicyCheckResponse.from(result);
	}
}
