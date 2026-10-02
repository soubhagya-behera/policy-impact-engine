package com.soubhagya.policyimpactengine.policy.application;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyOverviewResponse;

/**
 * Phase 17-A — read-only current-state projection of one owned policy for
 * the authenticated REST surface (see DECISIONS.md ADR-036).
 *
 * <p>The owner-scoped policy load is the single gate: an unknown or
 * foreign policy behaves as not-found (the single-resource 404 convention),
 * and only after that gate is passed are the three latest-fact lookups
 * performed. Status is never a filter — an archived owned policy stays
 * fully readable, exactly like the archive contract (ADR-030) requires.
 *
 * <p>Exactly four constant queries per call, independent of how much
 * history the policy has: the policy, the highest-numbered version, the
 * newest attempt ({@code startedAt DESC, id DESC}), and the newest
 * owner-and-policy-scoped assessment. No history feed is joined, fetched,
 * or counted, and mapping touches only scalar getters, so no lazy
 * association is initialized and no N+1 exists.
 *
 * <p>Strictly a read: no writes, no observation, no fetch, no scheduler,
 * no fan-out, and no audit emission. Nothing is recomputed or created — a
 * missing version, attempt, or assessment is reported as {@code null}.
 * The caller's user id always comes from the authenticated principal via
 * the controller; this service never touches the security context.
 */
@Service
public class PolicyOverviewReadService {

	private final PolicyRepository policyRepository;
	private final PolicyVersionRepository versionRepository;
	private final PolicyFetchAttemptRepository attemptRepository;
	private final ImpactAssessmentRepository assessmentRepository;

	public PolicyOverviewReadService(PolicyRepository policyRepository,
			PolicyVersionRepository versionRepository,
			PolicyFetchAttemptRepository attemptRepository,
			ImpactAssessmentRepository assessmentRepository) {
		if (policyRepository == null || versionRepository == null || attemptRepository == null
				|| assessmentRepository == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.policyRepository = policyRepository;
		this.versionRepository = versionRepository;
		this.attemptRepository = attemptRepository;
		this.assessmentRepository = assessmentRepository;
	}

	/**
	 * Returns the owner's compact current-state view of one policy. A
	 * foreign or unknown policy behaves as not-found; an owned policy with
	 * no version, no attempt, or no assessment still returns a populated
	 * overview whose missing latest-fact blocks are {@code null}.
	 */
	@Transactional(readOnly = true)
	public PolicyOverviewResponse get(UUID userId, UUID policyId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		Policy policy = policyRepository.findByIdAndOwner_Id(policyId, userId)
				.orElseThrow(() -> new NoSuchElementException(
						"Policy " + policyId + " not found"));
		// Ownership is already established above, so the latest-fact
		// lookups are policy-scoped only and never re-derive the owner.
		PolicyVersion latestVersion = versionRepository
				.findTopByPolicy_IdOrderByVersionNumberDesc(policyId).orElse(null);
		PolicyFetchAttempt latestCheck = attemptRepository
				.findFirstByPolicy_IdOrderByStartedAtDescIdDesc(policyId).orElse(null);
		ImpactAssessment latestImpact = assessmentRepository
				.findFirstByUser_IdAndNewVersion_Policy_IdOrderByCreatedAtDescIdDesc(userId, policyId)
				.orElse(null);
		return PolicyOverviewResponse.from(policy, latestVersion, latestCheck, latestImpact);
	}
}