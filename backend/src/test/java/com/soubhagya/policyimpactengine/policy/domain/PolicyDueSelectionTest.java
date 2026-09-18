package com.soubhagya.policyimpactengine.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 2T — Testcontainers tests for scheduled due-policy selection.
 *
 * <p>Covers the V10 migration (table change applied, backfill contract,
 * NOT NULL enforcement, due-query index) and the deterministic due query:
 * only ACTIVE policies at or past their next check time, ordered by
 * next check time then id.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PolicyDueSelectionTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyRepository policyRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		policyRepository.deleteAll();
	}

	@Test
	void flywayV10Applied() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version='10' AND success",
				Integer.class);
		assertThat(applied).isEqualTo(1);
	}

	@Test
	void newPoliciesStartDueImmediately() {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));

		assertThat(policy.getNextCheckAt()).isNotNull();
		assertThat(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, Instant.now())).extracting(Policy::getId)
				.contains(policy.getId());
	}

	@Test
	void dueBoundaryIncludesExactlyNow() {
		Instant now = Instant.parse("2026-09-18T10:00:00Z");
		Policy due = policyAt(PolicyStatus.ACTIVE, now);
		Policy past = policyAt(PolicyStatus.ACTIVE, now.minusSeconds(3600));
		Policy future = policyAt(PolicyStatus.ACTIVE, now.plusSeconds(3600));
		Policy archived = policyAt(PolicyStatus.ARCHIVED, now.minusSeconds(3600));

		List<Policy> duePolicies = policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, now);

		assertThat(duePolicies).extracting(Policy::getId)
				.containsExactlyInAnyOrder(due.getId(), past.getId());
		assertThat(duePolicies).extracting(Policy::getId)
				.doesNotContain(future.getId(), archived.getId());
	}

	@Test
	void archivedPoliciesAreNeverDue() {
		Instant now = Instant.parse("2026-09-18T10:00:00Z");
		policyAt(PolicyStatus.ARCHIVED, now.minusSeconds(7200));

		assertThat(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, now)).isEmpty();
	}

	@Test
	void orderingIsNextCheckAtThenId() {
		Instant now = Instant.parse("2026-09-18T10:00:00Z");
		Policy later = policyAt(PolicyStatus.ACTIVE, now.minusSeconds(10));
		Policy earlier = policyAt(PolicyStatus.ACTIVE, now.minusSeconds(3600));
		Policy tied1 = policyAt(PolicyStatus.ACTIVE, now.minusSeconds(60));
		Policy tied2 = policyAt(PolicyStatus.ACTIVE, now.minusSeconds(60));

		List<Policy> due = policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, now);

		// Tied next_check_at rows fall back to id order. PostgreSQL
		// compares uuid values unsigned, while Java UUID.compareTo is
		// signed-long based, so the expectation uses unsigned comparison
		// to pin the database's actual tie-break semantics.
		UUID firstTied = unsignedCompare(tied1.getId(), tied2.getId()) < 0 ? tied1.getId() : tied2.getId();
		UUID secondTied = firstTied.equals(tied1.getId()) ? tied2.getId() : tied1.getId();
		assertThat(due).extracting(Policy::getId).containsExactly(
				earlier.getId(), firstTied, secondTied, later.getId());
	}

	private static int unsignedCompare(UUID left, UUID right) {
		int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
		return high != 0 ? high
				: Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
	}

	@Test
	void nextCheckAtNotNullEnforced() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO policy (id, name, url, status, created_at, updated_at) "
						+ "VALUES (?, 'P', 'https://example.com/x', 'ACTIVE', now(), now())",
				id))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void dueQueryIndexExists() {
		Integer indexes = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pg_indexes WHERE tablename='policy' "
						+ "AND indexname='idx_policy_due_check'",
				Integer.class);
		assertThat(indexes).isEqualTo(1);
	}

	private Policy policyAt(PolicyStatus status, Instant nextCheckAt) {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setStatus(status);
		policy.setNextCheckAt(nextCheckAt);
		return policyRepository.saveAndFlush(policy);
	}
}
