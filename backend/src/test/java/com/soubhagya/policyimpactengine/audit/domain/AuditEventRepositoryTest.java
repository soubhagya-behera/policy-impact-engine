package com.soubhagya.policyimpactengine.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 11A — Testcontainers repository tests for {@link AuditEvent}.
 *
 * <p>Proves the V17 schema contract against real PostgreSQL: the
 * actor FK, the event-type CHECK, hash length CHECKs, the
 * single-genesis partial index, both chain UNIQUE guards, the head
 * lookup, and the newest-first actor-scoped feed.
 */
@SpringBootTest
@Testcontainers
class AuditEventRepositoryTest {

	private static final Instant FIRST = Instant.parse("2026-09-18T10:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private UserRepository userRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void genesisPersistsAndReadsBack() {
		User user = userRepository.saveAndFlush(new User());
		AuditEvent saved = auditEventRepository.saveAndFlush(new AuditEvent(user,
				AuditEventType.AUTH_USER_REGISTERED, "USER", user.getId(), FIRST, null,
				null, hash('a')));

		assertThat(saved.getId()).isNotNull();
		AuditEvent reloaded = auditEventRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getActorUser().getId()).isEqualTo(user.getId());
		assertThat(reloaded.getEventType()).isEqualTo(AuditEventType.AUTH_USER_REGISTERED);
		assertThat(reloaded.getOccurredAt()).isEqualTo(FIRST);
		assertThat(reloaded.getPrevHash()).isNull();
		assertThat(reloaded.getEventHash()).isEqualTo(hash('a'));
		assertThat(auditEventRepository.findChainHeads())
				.extracting(AuditEvent::getId).containsExactly(saved.getId());
	}

	@Test
	void actorForeignKeyIsEnforced() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO audit_event (id, occurred_at, actor_user_id, event_type, "
						+ "event_hash) VALUES (?, ?, TIMESTAMP '2026-09-18 10:00:00+00', ?, "
						+ "'AUTH_LOGIN_SUCCEEDED', ?)",
				UUID.randomUUID(), UUID.randomUUID(), hash('b')))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void eventTypeCheckRejectsUnknownCode() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO audit_event (id, occurred_at, event_type, event_hash) "
						+ "VALUES (?, TIMESTAMP '2026-09-18 10:00:00+00', 'BOGUS_EVENT', ?)",
				UUID.randomUUID(), hash('c')))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void eventHashLengthCheckRejectsShortValue() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO audit_event (id, occurred_at, event_type, event_hash) "
						+ "VALUES (?, TIMESTAMP '2026-09-18 10:00:00+00', "
						+ "'AUTH_LOGIN_SUCCEEDED', 'abc')",
				UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void singleGenesisIndexRejectsSecondNullPredecessor() {
		jdbcTemplate.update(
				"INSERT INTO audit_event (id, occurred_at, event_type, event_hash) "
						+ "VALUES (?, TIMESTAMP '2026-09-18 10:00:00+00', "
						+ "'AUTH_USER_REGISTERED', ?)",
				UUID.randomUUID(), hash('d'));

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO audit_event (id, occurred_at, event_type, event_hash) "
						+ "VALUES (?, TIMESTAMP '2026-09-18 10:00:01+00', "
						+ "'AUTH_USER_REGISTERED', ?)",
				UUID.randomUUID(), hash('e')))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(auditEventRepository.count()).isEqualTo(1);
	}

	@Test
	void uniquePredecessorRejectsSecondChild() {
		User user = userRepository.saveAndFlush(new User());
		AuditEvent genesis = auditEventRepository.saveAndFlush(new AuditEvent(user,
				AuditEventType.AUTH_USER_REGISTERED, null, null, FIRST, null, null,
				hash('f')));
		auditEventRepository.saveAndFlush(new AuditEvent(user,
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, FIRST.plusSeconds(1), null,
				genesis.getEventHash(), hash('0')));

		assertThatThrownBy(() -> auditEventRepository.saveAndFlush(new AuditEvent(user,
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, FIRST.plusSeconds(2), null,
				genesis.getEventHash(), hash('1'))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void uniqueEventHashRejectsDuplicateRow() {
		User user = userRepository.saveAndFlush(new User());
		auditEventRepository.saveAndFlush(new AuditEvent(user,
				AuditEventType.AUTH_USER_REGISTERED, null, null, FIRST, null, null,
				hash('2')));

		assertThatThrownBy(() -> auditEventRepository.saveAndFlush(new AuditEvent(user,
				AuditEventType.POLICY_REGISTERED, null, null, FIRST.plusSeconds(1),
				null, hash('2'), hash('2'))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void chainHeadFollowsTheChildlessRow() {
		User user = userRepository.saveAndFlush(new User());
		AuditEvent genesis = auditEventRepository.saveAndFlush(new AuditEvent(user,
				AuditEventType.AUTH_USER_REGISTERED, null, null, FIRST, null, null,
				hash('3')));
		assertThat(auditEventRepository.findChainHeads())
				.extracting(AuditEvent::getId).containsExactly(genesis.getId());

		AuditEvent child = auditEventRepository.saveAndFlush(new AuditEvent(user,
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, FIRST.plusSeconds(1), null,
				genesis.getEventHash(), hash('4')));
		assertThat(auditEventRepository.findChainHeads())
				.extracting(AuditEvent::getId).containsExactly(child.getId());
	}

	@Test
	void actorFeedIsNewestFirstAndIsolated() {
		User first = userRepository.saveAndFlush(new User());
		User second = userRepository.saveAndFlush(new User());
		AuditEvent older = auditEventRepository.saveAndFlush(new AuditEvent(first,
				AuditEventType.AUTH_USER_REGISTERED, null, null, FIRST, null, null,
				hash('5')));
		AuditEvent newer = auditEventRepository.saveAndFlush(new AuditEvent(first,
				AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null, FIRST.plusSeconds(60), null,
				older.getEventHash(), hash('6')));
		// The foreign row carries a dangling non-null predecessor: only one
		// genesis row may ever exist, so it cannot reuse NULL.
		AuditEvent foreign = auditEventRepository.saveAndFlush(new AuditEvent(second,
				AuditEventType.AUTH_USER_REGISTERED, null, null, FIRST.plusSeconds(30), null,
				hash('8'), hash('7')));

		List<AuditEvent> feed = auditEventRepository
				.findByActorUser_IdOrderByOccurredAtDescIdDesc(first.getId());
		assertThat(feed).extracting(AuditEvent::getId)
				.containsExactly(newer.getId(), older.getId());
		assertThat(auditEventRepository
				.findByActorUser_IdOrderByOccurredAtDescIdDesc(second.getId()))
				.extracting(AuditEvent::getId).containsExactly(foreign.getId());
	}

	@Test
	void emptyChainHasNoHead() {
		assertThat(auditEventRepository.findChainHeads()).isEmpty();
	}

	private static String hash(char fill) {
		return String.valueOf(fill).repeat(64);
	}
}
