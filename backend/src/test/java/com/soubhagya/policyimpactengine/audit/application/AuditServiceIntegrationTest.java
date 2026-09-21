package com.soubhagya.policyimpactengine.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditChain;
import com.soubhagya.policyimpactengine.audit.domain.AuditEvent;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Phase 11A — Testcontainers integration tests for
 * {@link AuditService}: genesis and chained appends, concurrent
 * convergence into one valid chain, fixed-clock determinism with
 * microsecond truncation, failure isolation, and the user feed.
 * No emission wiring exists yet; every row here is appended
 * directly through the service under test.
 */
@SpringBootTest
@Testcontainers
class AuditServiceIntegrationTest {

	private static final Instant FIRST = Instant.parse("2026-09-18T10:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private UserRepository userRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private AuditService auditService;
	@Autowired private PlatformTransactionManager transactionManager;

	@PersistenceContext
	private EntityManager entityManager;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void genesisAppendHasNullPredecessorAndVerifiableHash() {
		User user = userRepository.saveAndFlush(new User());

		AuditEvent genesis = auditService.append(user.getId(),
				AuditEventType.AUTH_USER_REGISTERED, "USER", user.getId(), null, FIRST);

		assertThat(genesis.getId()).isNotNull();
		assertThat(genesis.getPrevHash()).isNull();
		assertThat(genesis.getOccurredAt()).isEqualTo(FIRST);
		assertThat(genesis.getEventHash()).isEqualTo(recomputedHash(genesis));
		assertThat(auditEventRepository.count()).isEqualTo(1);
	}

	@Test
	void chainedAppendsLinkPredecessorHashes() {
		User user = userRepository.saveAndFlush(new User());
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("conceptCode", "LOCATION");
		fields.put("newSensitivity", 5);

		AuditEvent first = auditService.append(user.getId(),
				AuditEventType.AUTH_USER_REGISTERED, "USER", user.getId(), null, FIRST);
		AuditEvent second = auditService.append(user.getId(),
				AuditEventType.PRIVACY_PREFERENCE_UPSERTED, "USER", user.getId(),
				AuditMetadata.of(fields), FIRST.plusSeconds(60));
		AuditEvent third = auditService.append(null,
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				FIRST.plusSeconds(120));

		assertThat(second.getPrevHash()).isEqualTo(first.getEventHash());
		assertThat(third.getPrevHash()).isEqualTo(second.getEventHash());
		assertThat(third.getActorUser()).isNull();
		assertThat(auditEventRepository.findChainHeads())
				.extracting(AuditEvent::getId).containsExactly(third.getId());
		assertValidChain();
	}

	@Test
	void occurredAtDefaultsToClockAndTruncatesToMicros() {
		User user = userRepository.saveAndFlush(new User());
		Instant before = Instant.now();

		AuditEvent stamped = auditService.append(user.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null, null);
		AuditEvent precise = auditService.append(user.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				Instant.parse("2026-09-18T10:00:00.123456789Z"));

		Instant after = Instant.now();
		// Bounds use the truncated lower end: the stored value is the
		// microsecond truncation of a clock reading inside [before, after].
		assertThat(stamped.getOccurredAt())
				.isAfterOrEqualTo(before.truncatedTo(ChronoUnit.MICROS))
				.isBeforeOrEqualTo(after);
		assertThat(precise.getOccurredAt())
				.isEqualTo(Instant.parse("2026-09-18T10:00:00.123456Z"));
		assertValidChain();
	}

	@Test
	void concurrentAppendsConvergeIntoOneValidChain() throws Exception {
		User user = userRepository.saveAndFlush(new User());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<AuditEvent> first = executor.submit(() -> {
				start.await();
				return auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED,
						null, null, null, FIRST);
			});
			Future<AuditEvent> second = executor.submit(() -> {
				start.await();
				return auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED,
						null, null, null, FIRST.plusSeconds(1));
			});
			start.countDown();
			AuditEvent one = first.get(60, TimeUnit.SECONDS);
			AuditEvent two = second.get(60, TimeUnit.SECONDS);
			assertThat(one.getId()).isNotEqualTo(two.getId());
			assertThat(auditEventRepository.count()).isEqualTo(2);
			assertValidChain();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void persistentCollisionFailsTheAppendWithoutBusinessSideEffects() {
		AuditEventRepository failing = (AuditEventRepository) Proxy.newProxyInstance(
				AuditEventRepository.class.getClassLoader(),
				new Class<?>[] { AuditEventRepository.class },
				(proxy, method, args) -> {
					if (method.getName().equals("saveAndFlush")) {
						throw new DataIntegrityViolationException("forced collision");
					}
					return method.invoke(auditEventRepository, args);
				});
		AuditService failingService = new AuditService(failing, transactionManager,
				Clock.fixed(FIRST, ZoneOffset.UTC));
		ReflectionTestUtils.setField(failingService, "entityManager", entityManager);

		assertThatThrownBy(() -> failingService.append(null,
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null, FIRST))
				.isInstanceOf(AuditAppendException.class)
				.hasMessageContaining("5 attempts");
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void unknownActorFailsThroughTheForeignKey() {
		UUID missing = UUID.randomUUID();

		assertThatThrownBy(() -> auditService.append(missing,
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null, FIRST))
				.isInstanceOf(AuditAppendException.class);
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void invalidAppendArgumentsFailFastWithoutRows() {
		assertThatThrownBy(() -> auditService.append(UUID.randomUUID(), null, null, null,
				null, FIRST))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> auditService.listAuditEvents(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void userFeedIsScopedAndNewestFirst() {
		User first = userRepository.saveAndFlush(new User());
		User second = userRepository.saveAndFlush(new User());
		AuditEvent older = auditService.append(first.getId(),
				AuditEventType.AUTH_USER_REGISTERED, null, null, null, FIRST);
		AuditEvent newer = auditService.append(first.getId(),
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, null,
				FIRST.plusSeconds(60));
		AuditEvent foreign = auditService.append(second.getId(),
				AuditEventType.AUTH_USER_REGISTERED, null, null, null,
				FIRST.plusSeconds(30));

		assertThat(auditService.listAuditEvents(first.getId()))
				.extracting(AuditEvent::getId)
				.containsExactly(newer.getId(), older.getId());
		assertThat(auditService.listAuditEvents(second.getId()))
				.extracting(AuditEvent::getId).containsExactly(foreign.getId());
	}

	/**
	 * Full chain integrity: exactly one genesis, every non-genesis
	 * predecessor references a stored hash, no predecessor is shared,
	 * the walk from genesis visits every row exactly once, and every
	 * stored hash matches recomputation from persisted values.
	 */
	private void assertValidChain() {
		List<AuditEvent> rows = auditEventRepository.findAll();
		assertThat(rows).isNotEmpty();
		Map<String, AuditEvent> byHash = new HashMap<>();
		List<AuditEvent> geneses = new ArrayList<>();
		for (AuditEvent row : rows) {
			assertThat(byHash.put(row.getEventHash(), row)).isNull();
			if (row.getPrevHash() == null) {
				geneses.add(row);
			}
			assertThat(row.getEventHash()).isEqualTo(recomputedHash(row));
		}
		assertThat(geneses).hasSize(1);
		for (AuditEvent row : rows) {
			if (row.getPrevHash() != null) {
				assertThat(byHash).containsKey(row.getPrevHash());
			}
		}
		Map<String, Integer> childCount = new HashMap<>();
		for (AuditEvent row : rows) {
			if (row.getPrevHash() != null) {
				childCount.merge(row.getPrevHash(), 1, Integer::sum);
			}
		}
		assertThat(childCount.values()).allSatisfy(count -> assertThat(count).isEqualTo(1));
		List<UUID> visited = new ArrayList<>();
		AuditEvent current = geneses.get(0);
		while (current != null) {
			visited.add(current.getId());
			AuditEvent next = null;
			for (AuditEvent row : rows) {
				if (current.getEventHash().equals(row.getPrevHash())) {
					next = row;
				}
			}
			current = next;
		}
		assertThat(visited).hasSize(rows.size());
	}

	private String recomputedHash(AuditEvent row) {
		UUID actorId = row.getActorUser() == null ? null : row.getActorUser().getId();
		return AuditChain.canonicalHash(AuditChain.CANONICAL_FORMAT_VERSION,
				row.getEventType().name(), actorId, row.getResourceType(),
				row.getResourceId(), row.getOccurredAt(), row.getMetadata(),
				row.getPrevHash());
	}
}
