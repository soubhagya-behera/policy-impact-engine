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
 * Phase 14-C/2 — owner-scoped policy archive transition (see
 * DECISIONS.md ADR-030). The HTTP surface is implemented in
 * Phase 14-C/3 (DELETE /api/v1/policies/{policyId}), which
 * resolves identity exclusively from the authenticated
 * principal before delegating here.
 *
 * <p>Semantics: exactly one caller transitions an owned {@code
 * ACTIVE} policy to {@code ARCHIVED} through a single conditional
 * database update ({@code id + owner_id + status}); concurrent
 * archive attempts converge in the database with no Java
 * synchronization and no {@code @Version}. An already-archived
 * policy is a successful no-op that emits nothing. Unknown and
 * foreign policies behave as not-found, never forbidden, per the
 * owner-isolation convention.
 *
 * <p>Phase 14-C/2 emits {@code POLICY_ARCHIVED} only on the actual
 * transition, after the archive commits — a best-effort witness
 * that never rolls back the committed {@code ARCHIVED} state (see
 * DECISIONS.md ADR-022). No other audit event, no row deletion, no
 * scheduler/fan-out/observation/ownership change, and no
 * reactivation path exist in this slice.
 */
@Service
public class PolicyArchiveService {

	private final PolicyRepository repository;
	private final TransactionTemplate archiveTransaction;
	private final AuditService auditService;

	public PolicyArchiveService(PolicyRepository repository,
			PlatformTransactionManager transactionManager, AuditService auditService) {
		if (repository == null) {
			throw new IllegalArgumentException("PolicyRepository must not be null");
		}
		if (transactionManager == null || auditService == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.repository = repository;
		this.archiveTransaction = new TransactionTemplate(transactionManager);
		this.auditService = auditService;
	}

	/**
	 * Archives the user's policy: the single {@code ACTIVE → ARCHIVED}
	 * election, then at most one {@code POLICY_ARCHIVED} audit event.
	 *
	 * @param userId identifier of the owning user, from the
	 *        authenticated principal (never from request data)
	 * @param policyId identifier of the policy to archive
	 * @return true when the policy became {@code ARCHIVED} now, false
	 *         when it was already {@code ARCHIVED}
	 * @throws NoSuchElementException when the policy is unknown or
	 *         owned by another user
	 */
	public boolean archive(UUID userId, UUID policyId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		boolean transitioned = archiveTransaction.execute(
				status -> repository.archiveOwnedActive(policyId, userId,
						PolicyStatus.ACTIVE, PolicyStatus.ARCHIVED) == 1);
		if (transitioned) {
			auditService.append(userId, AuditEventType.POLICY_ARCHIVED, "POLICY",
					policyId, AuditMetadata.empty(), null);
			return true;
		}
		Policy policy = repository.findByIdAndOwner_Id(policyId, userId)
				.orElseThrow(() -> new NoSuchElementException("Policy " + policyId + " not found"));
		if (policy.getStatus() == PolicyStatus.ARCHIVED) {
			return false;
		}
		throw new IllegalStateException(
				"Policy " + policyId + " is not archivable from status " + policy.getStatus());
	}

}
