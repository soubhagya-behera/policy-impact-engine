package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.application.AuditAppendException;
import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.application.AuditVerificationService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 16-A/2 — Testcontainers integration tests for
 * {@link PolicyReactivationService} (see DECISIONS.md ADR-033).
 *
 * <p>Proves the mirror-image {@code ARCHIVED → ACTIVE} election: only
 * the status column moves, history and scheduling state are preserved,
 * exactly one {@code POLICY_REACTIVATED} audit row appears on the
 * actual transition, repeats and foreign/unknown rows stay silent or
 * not-found, audit failure never rolls back {@code ACTIVE}, and
 * concurrent reactivations converge with one winner and one audit.
 * The archive service itself is untouched here.
 */
@SpringBootTest
@Testcontainers
class PolicyReactivationServiceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyReactivationService reactivationService;
	@Autowired private PolicyArchiveService archiveService;
	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private AuditVerificationService verificationService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void ownedArchivedPolicyTransitionsToActivePreservingRow() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = archivedPolicy(owner);
		Policy before = policyRepository.findById(policy.getId()).orElseThrow();

		boolean transitioned = reactivationService.reactivate(owner.getId(), policy.getId());

		assertThat(transitioned).isTrue();
		Policy after = policyRepository.findById(policy.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
		assertThat(after.getName()).isEqualTo(before.getName());
		assertThat(after.getUrl()).isEqualTo(before.getUrl());
		assertThat(after.getOwner().getId()).isEqualTo(owner.getId());
		assertThat(after.getNextCheckAt()).isEqualTo(before.getNextCheckAt());
		assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
	}

	@Test
	void reactivationEmitsExactlyOnePolicyReactivatedEvent() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = archivedPolicy(owner);

		reactivationService.reactivate(owner.getId(), policy.getId());

		List<Map<String, Object>> rows = rowsByType("POLICY_REACTIVATED");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("actor_user_id")).isEqualTo(owner.getId());
		assertThat(rows.get(0).get("resource_type")).isEqualTo("POLICY");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(policy.getId());
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");
		assertThat(auditEventRepository.count()).isEqualTo(2);
	}

	@Test
	void reactivationPreservesVersionHistory() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = archivedPolicy(owner);
		versionRepository.saveAndFlush(new PolicyVersion(policy, 1,
				"Archived content.", "a".repeat(64)));

		reactivationService.reactivate(owner.getId(), policy.getId());

		assertThat(versionRepository.count()).isEqualTo(1);
		assertThat(versionRepository.findByPolicy_IdAndVersionNumber(
				policy.getId(), 1)).isPresent();
		assertThat(versionRepository.findAll().get(0).getNormalizedContent())
				.isEqualTo("Archived content.");
	}

	@Test
	void alreadyActiveIsSilentNoOp() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);

		boolean repeated = reactivationService.reactivate(owner.getId(), policy.getId());

		assertThat(repeated).isFalse();
		assertThat(policyRepository.findById(policy.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ACTIVE);
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void unknownAndForeignPoliciesBehaveAsNotFound() {
		User owner = userRepository.saveAndFlush(new User());
		User stranger = userRepository.saveAndFlush(new User());
		Policy policy = archivedPolicy(owner);
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> reactivationService.reactivate(owner.getId(), unknown))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining(unknown.toString());
		assertThatThrownBy(() -> reactivationService.reactivate(stranger.getId(), policy.getId()))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining(policy.getId().toString());

		assertThat(policyRepository.findById(policy.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(countByType("POLICY_REACTIVATED")).isZero();
	}

	@Test
	void auditFailureDoesNotRollBackActiveState() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = archivedPolicy(owner);
		AuditService failingAudit = mock(AuditService.class);
		doThrow(new AuditAppendException("forced audit failure", null))
				.when(failingAudit).append(any(), any(), any(), any(), any(), any());
		PolicyReactivationService failingService = new PolicyReactivationService(policyRepository,
				transactionManager, failingAudit);

		assertThatThrownBy(() -> failingService.reactivate(owner.getId(), policy.getId()))
				.isInstanceOf(AuditAppendException.class);

		assertThat(policyRepository.findById(policy.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ACTIVE);
		assertThat(countByType("POLICY_REACTIVATED")).isZero();
	}

	@Test
	void nullIdsRejected() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> reactivationService.reactivate(null, id))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reactivationService.reactivate(id, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void concurrentReactivationConvergesToOneTransitionAndOneAudit() throws Exception {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = archivedPolicy(owner);
		int contenders = 8;
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		CountDownLatch ready = new CountDownLatch(contenders);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < contenders; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					if (!start.await(30, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Start gate timed out");
					}
					return reactivationService.reactivate(owner.getId(), policy.getId());
				}));
			}
			assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<Boolean> outcomes = new ArrayList<>();
			for (Future<Boolean> future : futures) {
				outcomes.add(future.get(60, TimeUnit.SECONDS));
			}
			assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
			assertThat(outcomes.stream().filter(outcome -> !outcome).count())
					.isEqualTo(contenders - 1);
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(policyRepository.findById(policy.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ACTIVE);
		assertThat(countByType("POLICY_REACTIVATED")).isEqualTo(1);
	}

	@Test
	void archiveVsReactivationRaceFollowsPredicateSemantics() throws Exception {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Future<Boolean> archived;
		Future<Boolean> reactivated;
		try {
			archived = pool.submit(() -> {
				ready.countDown();
				if (!start.await(30, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Start gate timed out");
				}
				return archiveService.archive(owner.getId(), policy.getId());
			});
			reactivated = pool.submit(() -> {
				ready.countDown();
				if (!start.await(30, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Start gate timed out");
				}
				return reactivationService.reactivate(owner.getId(), policy.getId());
			});
			assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertThat(archived.get(60, TimeUnit.SECONDS)).isTrue();
			assertThat(reactivated.get(60, TimeUnit.SECONDS)).isIn(true, false);
		}
		finally {
			pool.shutdownNow();
		}

		PolicyStatus finalStatus =
				policyRepository.findById(policy.getId()).orElseThrow().getStatus();
		if (finalStatus == PolicyStatus.ACTIVE) {
			assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);
			assertThat(countByType("POLICY_REACTIVATED")).isEqualTo(1);
		}
		else {
			assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);
			assertThat(countByType("POLICY_REACTIVATED")).isZero();
		}
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	private Policy ownedPolicy(User owner) {
		Policy policy = new Policy("Acme", "https://example.com/" + UUID.randomUUID());
		policy.setOwner(owner);
		return policyRepository.saveAndFlush(policy);
	}

	private Policy archivedPolicy(User owner) {
		Policy policy = ownedPolicy(owner);
		assertThat(archiveService.archive(owner.getId(), policy.getId())).isTrue();
		return policy;
	}

	private int countByType(String eventType) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ?",
				Integer.class, eventType);
		return count == null ? 0 : count;
	}

	private List<Map<String, Object>> rowsByType(String eventType) {
		return jdbcTemplate.queryForList("SELECT actor_user_id, resource_type, resource_id, "
				+ "metadata FROM audit_event WHERE event_type = ? ORDER BY occurred_at, id",
				eventType);
	}
}
