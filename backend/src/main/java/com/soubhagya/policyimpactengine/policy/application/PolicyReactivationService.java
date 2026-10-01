package com.soubhagya.policyimpactengine.policy.application;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;

/**
 * Phase 16-A/2 — owner-scoped policy reactivation transition (see
 * DECISIONS.md ADR-033). A separate service from
 * {@link PolicyArchiveService} — one service per transition direction —
 * so the archive contract stays byte-identical. The HTTP surface is
 * {@code POST /api/v1/policies/{policyId}/reactivate}, which resolves
 * identity exclusively from the authenticated principal before
 * delegating here.
 *
 * <p>Semantics: exactly one caller transitions an owned {@code
 * ARCHIVED} policy to {@code ACTIVE} through a single conditional
 * database update ({@code id + owner_id + status}); concurrent
 * reactivations converge in the database with no Java synchronization
 * and no {@code @Version}. A reactivation racing an archive resolves
 * per predicate with last-writer-wins composition: a lost election
 * that re-reads an owned {@code ARCHIVED} row re-enters the election
 * (bounded retries) instead of failing, so every owned-row caller
 * observes success. An already-active policy is a successful no-op
 * that emits nothing. Unknown and foreign policies behave as
 * not-found, never forbidden, per the owner-isolation convention.
 * Only the {@code status} column is ever written: scheduling state,
 * history, and ownership are untouched, and no observation is
 * triggered — the policy simply rejoins the unchanged scheduler tick.
 *
 * <p>Phase 16-A/2 emits {@code POLICY_REACTIVATED} only on the actual
 * transition, after the reactivation commits — a best-effort witness
 * that never rolls back the committed {@code ACTIVE} state (see
 * DECISIONS.md ADR-022). No other audit event, no row deletion, no
 * history backfill, and no scheduler/fan-out/observation change exist
 * in this slice.
 */
@Service
public class PolicyReactivationService {

	/**
	 * Bounded re-election budget for a lost election that re-reads an
	 * owned {@code ARCHIVED} row (a concurrent archive won between the
	 * predicate and the re-read). Each further attempt requires another
	 * concurrent archive commit, so three elections bound pathological
	 * contention without retrying forever.
	 */
	static final int MAX_ELECTION_ATTEMPTS = 3;

	private final PolicyRepository repository;
	private final TransactionTemplate reactivationTransaction;
	private final AuditService auditService;

	public PolicyReactivationService(PolicyRepository repository,
			PlatformTransactionManager transactionManager, AuditService auditService) {
		if (repository == null) {
			throw new IllegalArgumentException("PolicyRepository must not be null");
		}
		if (transactionManager == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.repository = repository;
		this.reactivationTransaction = new TransactionTemplate(transactionManager);
		this.auditService = auditService;
	}

	/**
	 * Reactivates the user's policy: the single {@code ARCHIVED → ACTIVE}
	 * election, then at most one {@code POLICY_REACTIVATED} audit event.
	 *
	 * @param userId identifier of the owning user, from the
	 *        authenticated principal (never from request data)
	 * @param policyId identifier of the policy to reactivate
	 * @return true when the policy became {@code ACTIVE} now, false
	 *         when it was already {@code ACTIVE}
	 * @throws NoSuchElementException when the policy is unknown or
	 *         owned by another user
	 */
	public boolean reactivate(UUID userId, UUID policyId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		for (int attempt = 0; attempt < MAX_ELECTION_ATTEMPTS; attempt++) {
			boolean transitioned = reactivationTransaction.execute(
					status -> repository.reactivateOwnedArchived(policyId, userId,
							PolicyStatus.ARCHIVED, PolicyStatus.ACTIVE) == 1);
			if (transitioned) {
				auditService.append(userId, AuditEventType.POLICY_REACTIVATED, "POLICY",
						policyId, AuditMetadata.empty(), null);
				return true;
			}
			Policy policy = repository.findByIdAndOwner_Id(policyId, userId)
					.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));
			if (policy.getStatus() == PolicyStatus.ACTIVE) {
				return false;
			}
			if (attempt == MAX_ELECTION_ATTEMPTS - 1) {
				throw new IllegalStateException("Policy " + policyId
						+ " is not reactivatable from status " + policy.getStatus());
			}
		}
		throw new IllegalStateException("Reactivation election did not converge for policy "
				+ policyId);
	}

}
