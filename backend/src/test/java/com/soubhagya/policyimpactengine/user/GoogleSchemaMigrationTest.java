package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.user.domain.GoogleCompletionRepository;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 18-B — schema tests for Flyway V25–V27 (see DECISIONS.md
 * ADR-037).
 *
 * <p>Proves V25–V27 apply cleanly with V1–V24 untouched: {@code
 * app_user.google_sub} is nullable with a partial unique index (any
 * number of NULL rows coexist, every non-null subject is globally
 * unique), the completion table carries its uniqueness/expiry guards,
 * and the audit {@code event_type} CHECK holds the new Google code.
 * No service behavior is exercised here.
 */
@SpringBootTest
@Testcontainers
class GoogleSchemaMigrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private GoogleCompletionRepository completionRepository;

	@Autowired
	private AuditEventRepository auditEventRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		completionRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void v25ToV27MigrationsApply() {
		for (String version : new String[] { "25", "26", "27" }) {
			Integer applied = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM flyway_schema_history WHERE version=? AND success=true",
					Integer.class, version);
			assertThat(applied).as("Migration V%s", version).isEqualTo(1);
		}
	}

	@Test
	void googleSubIsNullableWithPartialUniqueIndex() {
		String nullable = jdbcTemplate.queryForObject(
				"SELECT is_nullable FROM information_schema.columns "
						+ "WHERE table_name='app_user' AND column_name='google_sub'",
				String.class);
		assertThat(nullable).isEqualTo("YES");

		String predicate = jdbcTemplate.queryForObject(
				"SELECT pg_get_indexdef(indexrelid) FROM pg_index "
						+ "WHERE indexrelid='uq_app_user_google_sub'::regclass",
				String.class);
		assertThat(predicate).contains("WHERE (google_sub IS NOT NULL)");
	}

	@Test
	void duplicateGoogleSubIsRejectedAtTheDatabase() {
		User first = new User();
		first.assignGoogleIdentity("first@example.com", "duplicated-sub");
		userRepository.saveAndFlush(first);

		User second = new User();
		second.assignGoogleIdentity("second@example.com", "duplicated-sub");
		assertThatThrownBy(() -> userRepository.saveAndFlush(second))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void nullGoogleSubsCoexist() {
		User first = new User();
		first.assignCredentials("plain-one@example.com", "hash-one");
		User second = new User();
		second.assignCredentials("plain-two@example.com", "hash-two");
		userRepository.saveAll(java.util.List.of(first, second));
		userRepository.flush();

		assertThat(userRepository.findByGoogleSub("missing")).isEmpty();
	}

	@Test
	void completionTableCarriesUniquenessAndExpiryGuards() {
		assertThat(jdbcTemplate.queryForList(
				"SELECT conname FROM pg_constraint "
						+ "WHERE conrelid='auth_google_completion'::regclass "
						+ "AND contype IN ('p', 'f', 'u', 'c')",
				String.class))
				.contains("uq_auth_google_completion_hash",
						"chk_auth_google_completion_expiry");

		// Foreign key: an unknown owner is rejected.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO auth_google_completion (id, user_id, code_hash, created_at,"
						+ " expires_at) VALUES (gen_random_uuid(), gen_random_uuid(), ?,"
						+ " now(), now() + interval '1 minute')",
				"a".repeat(64)))
				.isInstanceOf(DataIntegrityViolationException.class);

		// Expiry guard: expiry must stay after creation.
		User owner = new User();
		owner.assignCredentials("guard@example.com", "hash-guard");
		userRepository.saveAndFlush(owner);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO auth_google_completion (id, user_id, code_hash, created_at,"
						+ " expires_at) VALUES (gen_random_uuid(), ?, ?, now(), now())",
				owner.getId(), "b".repeat(64)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void auditCheckHoldsTheGoogleLoginCode() {
		String definition = jdbcTemplate.queryForObject(
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?",
				String.class, "audit_event_event_type_check");
		assertThat(definition).contains("'AUTH_GOOGLE_LOGIN_SUCCEEDED'");
	}
}
