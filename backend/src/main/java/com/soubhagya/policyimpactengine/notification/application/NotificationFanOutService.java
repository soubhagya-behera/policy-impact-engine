package com.soubhagya.policyimpactengine.notification.application;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;

/**
 * Phase 10B-1 — automatic service-layer fan-out from a successful
 * policy observation to the policy's single owner.
 *
 * <p>Eligibility is conjunctive: the observation outcome must be
 * {@code NEW_VERSION}, the policy must be {@code ACTIVE}, and the
 * policy must have a non-null owner. Every other case —
 * {@code FIRST_VERSION}, {@code UNCHANGED}, failures (which never
 * reach this service as results), skipped/claim collisions, inactive
 * policies, and unowned policies — returns silently. An unowned
 * policy keeps being observed normally but produces no assessment,
 * no recommendation, and no notification.
 *
 * <p>For an eligible {@code NEW_VERSION} the flow reuses the existing
 * services without duplicating their rules:
 *
 * <pre>
 * policy owner → derive newVersionId →
 * ImpactAssessmentService.getOrCreateAssessment(ownerId, newVersionId) →
 * RecommendationService.getOrCreateRecommendations(ownerId, newVersionId) →
 * existing Phase 10A notification emission hook
 * </pre>
 *
 * <p>The version UUID is derived through the existing repository query
 * {@code findByPolicy_IdAndVersionNumber(policyId, versionNumber)};
 * {@link PolicyObservationResult} is not modified to carry the version
 * ID.
 *
 * <p><b>Transactions:</b> this method opens no encompassing
 * transaction. It performs short read lookups and then delegates to
 * the existing short transactions in order: assessment TX, then
 * recommendation TX (which in turn runs the notification TX). No HTTP
 * is involved.
 *
 * <p><b>Idempotency and concurrency:</b> no Java synchronization and
 * no new infrastructure. Repeats converge through the existing
 * uniqueness guards — {@code UNIQUE(user_id, new_version_id)} on the
 * assessment, the Phase 2R recommendation uniqueness, and
 * {@code UNIQUE(assessment_id)} on the notification — backed by the
 * V11 observation claim serialization upstream.
 *
 * <p><b>Failure isolation:</b> this service runs after the
 * observation itself has succeeded. A fan-out failure propagates to
 * the caller (the scheduler isolates it per policy) and never rewrites
 * the observation: no {@code PolicyFetchAttempt} change, no
 * {@code next_check_at} change, no {@code FAILED} marking, no
 * observation retry, no V11/V12/V13 change. The accepted healable gap
 * is that the observation stays successful while its personalized
 * fan-out is missing; the next successful observation's fan-out — or a
 * repeated fan-out for the same version — heals it idempotently
 * because every step is get-or-create.
 */
@Service
public class NotificationFanOutService {

	private final PolicyRepository policyRepository;
	private final PolicyVersionRepository versionRepository;
	private final ImpactAssessmentService assessmentService;
	private final RecommendationService recommendationService;

	public NotificationFanOutService(PolicyRepository policyRepository,
			PolicyVersionRepository versionRepository,
			ImpactAssessmentService assessmentService,
			RecommendationService recommendationService) {
		if (policyRepository == null) {
			throw new IllegalArgumentException("PolicyRepository must not be null");
		}
		if (versionRepository == null) {
			throw new IllegalArgumentException("PolicyVersionRepository must not be null");
		}
		if (assessmentService == null) {
			throw new IllegalArgumentException("ImpactAssessmentService must not be null");
		}
		if (recommendationService == null) {
			throw new IllegalArgumentException("RecommendationService must not be null");
		}
		this.policyRepository = policyRepository;
		this.versionRepository = versionRepository;
		this.assessmentService = assessmentService;
		this.recommendationService = recommendationService;
	}

	/**
	 * Fans out a successful observation result to the policy's owner
	 * when — and only when — the result is an eligible
	 * {@code NEW_VERSION}. All ineligible cases return silently.
	 *
	 * @param policyId identifier of the observed policy
	 * @param result the successful observation result; never modified
	 */
	public void fanOut(UUID policyId, PolicyObservationResult result) {
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		if (result == null) {
			throw new IllegalArgumentException("Observation result must not be null");
		}
		if (!policyId.equals(result.policyId())) {
			throw new IllegalArgumentException("Observation result belongs to a different policy");
		}
		if (result.outcome() != PolicyVersionObservationOutcome.NEW_VERSION) {
			return;
		}
		Policy policy = policyRepository.findById(policyId)
				.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));
		if (policy.getStatus() != PolicyStatus.ACTIVE) {
			return;
		}
		if (policy.getOwner() == null) {
			return;
		}
		UUID ownerId = policy.getOwner().getId();
		PolicyVersion newVersion = versionRepository
				.findByPolicy_IdAndVersionNumber(policyId, result.versionNumber())
				.orElseThrow(() -> new NoSuchElementException(
						"Policy version " + result.versionNumber() + " for policy " + policyId
								+ " not found"));
		UUID newVersionId = newVersion.getId();
		assessmentService.getOrCreateAssessment(ownerId, newVersionId);
		recommendationService.getOrCreateRecommendations(ownerId, newVersionId);
	}
}
