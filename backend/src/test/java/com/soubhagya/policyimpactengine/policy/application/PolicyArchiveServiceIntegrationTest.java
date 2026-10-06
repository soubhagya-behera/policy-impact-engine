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
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 14-C/2 — Testcontainers proof for the archive transition
 * service (see DECISIONS.md ADR-030): one owned {@code ACTIVE}
 * policy becomes {@code ARCHIVED} through a single conditional
 * database update, then emits exactly one {@code POLICY_ARCHIVED}
 * audit event post-commit. Unknown/foreign policies behave as
 * not-found, repeats are silent no-ops, concurrent attempts
 * converge to one transition and one event, and an audit failure
 * never rolls back the committed {@code ARCHIVED} state. No HTTP,
 * scheduler, fan-out, or observation behavior is exercised here.
 */
@SpringBootTest
@Testcontainers
class PolicyArchiveServiceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyArchiveService archiveService;
	@Autowired private PolicyRepository policyRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void ownedActivePolicyTransitionsToArchived() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);
		Policy before = policyRepository.findById(policy.getId()).orElseThrow();

		boolean transitioned = archiveService.archive(owner.getId(), policy.getId());

		assertThat(transitioned).isTrue();
		Policy after = policyRepository.findById(policy.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(after.getName()).isEqualTo(before.getName());
		assertThat(after.getUrl()).isEqualTo(before.getUrl());
		assertThat(after.getOwner().getId()).isEqualTo(owner.getId());
		assertThat(after.getNextCheckAt()).isEqualTo(before.getNextCheckAt());
		assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
	}

	@Test
	void archiveEmitsExactlyOnePolicyArchivedEvent() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);

		archiveService.archive(owner.getId(), policy.getId());

		List<Map<String, Object>> rows = rowsByType("POLICY_ARCHIVED");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("actor_user_id")).isEqualTo(owner.getId());
		assertThat(rows.get(0).get("resource_type")).isEqualTo("POLICY");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(policy.getId());
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");
		assertThat(auditEventRepository.count()).isEqualTo(1);
	}

	@Test
	void unknownPolicyBehavesAsNotFound() {
		User owner = userRepository.saveAndFlush(new User());
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> archiveService.archive(owner.getId(), unknown))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining(unknown.toString());
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void foreignPolicyBehavesAsNotFound() {
		User owner = userRepository.saveAndFlush(new User());
		User stranger = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);

		assertThatThrownBy(() -> archiveService.archive(stranger.getId(), policy.getId()))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining(policy.getId().toString());
		assertThat(policyRepository.findById(policy.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ACTIVE);
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void alreadyArchivedIsSilentNoOp() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);
		assertThat(archiveService.archive(owner.getId(), policy.getId())).isTrue();

		boolean repeated = archiveService.archive(owner.getId(), policy.getId());

		assertThat(repeated).isFalse();
		assertThat(policyRepository.findById(policy.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);
	}

	@Test
	void concurrentArchiveConvergesToOneTransitionAndOneAudit() throws Exception {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);
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
					return archiveService.archive(owner.getId(), policy.getId());
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
				.isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);
	}

	@Test
	void auditFailureDoesNotRollBackArchivedState() {
		User owner = userRepository.saveAndFlush(new User());
		Policy policy = ownedPolicy(owner);
		AuditService failingAudit = mock(AuditService.class);
		doThrow(new AuditAppendException("forced audit failure", null))
				.when(failingAudit).append(any(), any(), any(), any(), any(), any());
		PolicyArchiveService failingService = new PolicyArchiveService(policyRepository,
				transactionManager, failingAudit);

		assertThatThrownBy(() -> failingService.archive(owner.getId(), policy.getId()))
				.isInstanceOf(AuditAppendException.class);

		assertThat(policyRepository.findById(policy.getId()).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void nullIdsRejected() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> archiveService.archive(null, id))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> archiveService.archive(id, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void v20AuditCatalogRemainsValid() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
				Integer.class);
		assertThat(applied).isEqualTo(27);

		String definition = jdbcTemplate.queryForObject(
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
				String.class, "audit_event_event_type_check");
		assertThat(definition).isNotNull();
		for (String code : List.of("AUTH_USER_REGISTERED", "AUTH_LOGIN_SUCCEEDED",
				"POLICY_REGISTERED", "POLICY_OWNER_ASSIGNED",
				"PRIVACY_PREFERENCE_UPSERTED", "PRIVACY_PREFERENCE_DELETED",
				"POLICY_ARCHIVED")) {
			assertThat(definition).contains("'" + code + "'");
		}
	}

	private Policy ownedPolicy(User owner) {
		Policy policy = new Policy("Acme", "https://example.com/" + UUID.randomUUID());
		policy.setOwner(owner);
		return policyRepository.saveAndFlush(policy);
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
