package com.soubhagya.policyimpactengine.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

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

/**
 * Phase 15-A/2 — Testcontainers schema/catalog tests for Flyway V21
 * (see DECISIONS.md ADR-031).
 *
 * <p>Proves V1–V21 apply cleanly with V1–V20 untouched, the
 * {@code event_type} CHECK is still the verified V17 constraint
 * {@code audit_event_event_type_check} widened to exactly nine codes,
 * the enum and the CHECK stay in lockstep, all nine codes persist,
 * unknown codes are still rejected, and no extra schema objects were
 * introduced. No audit emission, service behavior, or domain logic is
 * exercised here.
 */
@SpringBootTest
@Testcontainers
class AuditEventCatalogV21Test {

	private static final List<String> NINE_CODES = List.of(
			"AUTH_USER_REGISTERED",
			"AUTH_LOGIN_SUCCEEDED",
			"POLICY_REGISTERED",
			"POLICY_OWNER_ASSIGNED",
			"PRIVACY_PREFERENCE_UPSERTED",
			"PRIVACY_PREFERENCE_DELETED",
			"POLICY_ARCHIVED",
			"AUTH_LOGOUT_SUCCEEDED",
			"AUTH_LOGOUT_ALL_SUCCEEDED");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AuditEventRepository auditEventRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
	}

	@Test
	void v21MigrationApplies() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version='21' AND success=true",
				Integer.class);
		assertThat(applied).isEqualTo(1);
	}

	@Test
	void v1ToV24ValidationRemainsClean() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE success=true", Integer.class);
		assertThat(applied).isEqualTo(24);
		Integer failed = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE success=false", Integer.class);
		assertThat(failed).isZero();
	}

	@Test
	void v20RemainsApplied() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version='20' AND success=true",
				Integer.class);
		assertThat(applied).isEqualTo(1);
	}

	@Test
	void eventTypeConstraintIsTheVerifiedNameWithNineCodes() {
		String name = jdbcTemplate.queryForObject(
				"SELECT conname FROM pg_constraint WHERE conrelid='audit_event'::regclass "
						+ "AND contype='c' AND pg_get_constraintdef(oid) LIKE '%event_type%'",
				String.class);
		assertThat(name).isEqualTo("audit_event_event_type_check");

		String definition = jdbcTemplate.queryForObject(
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?",
				String.class, "audit_event_event_type_check");
		assertThat(definition).isNotNull();
		for (String code : NINE_CODES) {
			assertThat(definition).contains("'" + code + "'");
		}
	}

	/**
	 * Phase 15-B/2 — V22 widened the catalog to ten codes (see DECISIONS.md
	 * ADR-032), so exact equality lives in {@code AuditEventCatalogV22Test}.
	 * This V21-era test now proves the subset it introduced: all nine V21
	 * codes remain in both the enum and the CHECK.
	 */
	@Test
	void v21CodesRemainInEnumAndCheck() {
		List<String> enumCodes = java.util.Arrays.stream(AuditEventType.values())
				.map(Enum::name).sorted().toList();
		assertThat(enumCodes).containsAll(NINE_CODES);

		String definition = jdbcTemplate.queryForObject(
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?",
				String.class, "audit_event_event_type_check");
		assertThat(definition).isNotNull();
		for (String code : NINE_CODES) {
			assertThat(definition).contains("'" + code + "'");
		}
	}

	@Test
	void allNineCodesPersist() {
		String previous = null;
		for (int i = 0; i < NINE_CODES.size(); i++) {
			String eventHash = hash((char) ('p' + i));
			jdbcTemplate.update(
					"INSERT INTO audit_event (id, occurred_at, event_type, prev_hash, event_hash) "
							+ "VALUES (gen_random_uuid(), "
							+ "TIMESTAMP '2026-09-28 10:00:00+00' + (? || ' seconds')::interval, "
							+ "?, ?, ?)",
					i, NINE_CODES.get(i), previous, eventHash);
			previous = eventHash;
		}

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(9);
		assertThat(jdbcTemplate.queryForList(
				"SELECT DISTINCT event_type FROM audit_event ORDER BY event_type", String.class))
				.containsExactlyInAnyOrderElementsOf(NINE_CODES);
	}

	@Test
	void unknownCodeStillRejected() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO audit_event (id, occurred_at, event_type, event_hash) "
						+ "VALUES (gen_random_uuid(), TIMESTAMP '2026-09-28 10:00:00+00', "
						+ "'BOGUS_EVENT', ?)",
				hash('z')))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void noExtraSchemaObjectsIntroduced() {
		assertThat(jdbcTemplate.queryForList(
				"SELECT column_name FROM information_schema.columns "
						+ "WHERE table_name='audit_event' ORDER BY ordinal_position",
				String.class))
				.containsExactly("id", "occurred_at", "actor_user_id", "event_type",
						"resource_type", "resource_id", "metadata", "prev_hash", "event_hash");
		assertThat(jdbcTemplate.queryForList(
				"SELECT conname FROM pg_constraint "
						+ "WHERE conrelid='audit_event'::regclass "
						+ "AND contype IN ('p', 'f', 'u', 'c')",
				String.class))
				.containsExactlyInAnyOrder("audit_event_pkey",
						"audit_event_actor_user_id_fkey",
						"audit_event_event_hash_check",
						"audit_event_event_type_check",
						"audit_event_prev_hash_check",
						"uq_audit_event_hash",
						"uq_audit_event_prev_hash");
		assertThat(jdbcTemplate.queryForList(
				"SELECT indexname FROM pg_indexes WHERE tablename='audit_event'",
				String.class))
				.containsExactlyInAnyOrder("audit_event_pkey",
						"uq_audit_event_hash",
						"uq_audit_event_prev_hash",
						"uq_audit_event_single_genesis",
						"idx_audit_event_actor_time");
	}

	private static String hash(char fill) {
		return String.valueOf(fill).repeat(64);
	}
}
