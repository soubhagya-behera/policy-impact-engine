package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

import com.soubhagya.policyimpactengine.user.domain.RefreshToken;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;

/**
 * Phase 14-A/2a — Testcontainers persistence/invariant tests for the V19
 * refresh-token foundation (see DECISIONS.md ADR-029): migration applies,
 * V1-V19 history stays clean, schema matches the ADR exactly, digests are
 * unique, the owner FK and expiry CHECK hold, nullables behave, lookup and
 * the sanctioned bulk operations work, purge stays bounded to expired
 * rows, and no raw token is ever persisted.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RefreshTokenRepositoryTest {

	private static final Instant CREATED = Instant.parse("2026-09-20T10:00:00Z");
	private static final Instant EXPIRES = Instant.parse("2026-10-20T10:00:00Z");
	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
	private static final Instant EXPIRED_CREATED = Instant.parse("2026-08-01T10:00:00Z");
	private static final Instant EXPIRED_AT = Instant.parse("2026-08-31T10:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void v19MigrationApplies() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version='19' AND success=true",
				Integer.class);
		assertThat(applied).isEqualTo(1);
	}

	@Test
	void v1ToV19ValidationRemainsClean() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE success=true", Integer.class);
		assertThat(applied).isEqualTo(19);
		Integer failed = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE success=false", Integer.class);
		assertThat(failed).isZero();
	}

	@Test
	void schemaMatchesAdr029Exactly() {
		List<String> columns = jdbcTemplate.queryForList(
				"SELECT column_name FROM information_schema.columns "
						+ "WHERE table_name='auth_refresh_token' ORDER BY ordinal_position",
				String.class);
		assertThat(columns).containsExactly("id", "user_id", "token_hash", "created_at",
				"expires_at", "revoked_at", "replaced_by_token_hash");
		Integer unique = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints "
						+ "WHERE table_name='auth_refresh_token' "
						+ "AND constraint_name='uq_auth_refresh_token_hash' "
						+ "AND constraint_type='UNIQUE'",
				Integer.class);
		assertThat(unique).isEqualTo(1);
		Integer index = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pg_indexes WHERE tablename='auth_refresh_token' "
						+ "AND indexname='idx_auth_refresh_token_user_expiry'",
				Integer.class);
		assertThat(index).isEqualTo(1);
		Integer check = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints "
						+ "WHERE table_name='auth_refresh_token' "
						+ "AND constraint_name='chk_auth_refresh_token_expiry' "
						+ "AND constraint_type='CHECK'",
				Integer.class);
		assertThat(check).isEqualTo(1);
	}

	@Test
	void persistenceRoundTripAndLookupByTokenHash() {
		User user = userRepository.saveAndFlush(new User());
		String hash = "a".repeat(64);
		RefreshToken saved = refreshTokenRepository
				.saveAndFlush(new RefreshToken(user, hash, CREATED, EXPIRES));

		RefreshToken row = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
		assertThat(row.getId()).isEqualTo(saved.getId());
		assertThat(row.getUser().getId()).isEqualTo(user.getId());
		assertThat(row.getTokenHash()).isEqualTo(hash);
		assertThat(row.getCreatedAt()).isEqualTo(CREATED);
		assertThat(row.getExpiresAt()).isEqualTo(EXPIRES);
		assertThat(row.getRevokedAt()).isNull();
		assertThat(row.getReplacedByTokenHash()).isNull();
		assertThat(refreshTokenRepository.findByTokenHash("b".repeat(64))).isEmpty();
	}

	@Test
	void duplicateTokenHashIsRejected() {
		User user = userRepository.saveAndFlush(new User());
		refreshTokenRepository.saveAndFlush(new RefreshToken(user, "a".repeat(64), CREATED, EXPIRES));

		RefreshToken duplicate = new RefreshToken(user, "a".repeat(64), CREATED, EXPIRES);
		assertThatThrownBy(() -> refreshTokenRepository.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unknownUserIsRejectedByForeignKey() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO auth_refresh_token (id, user_id, token_hash, created_at, expires_at) "
						+ "VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64), Timestamp.from(CREATED),
				Timestamp.from(EXPIRES)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void expiryCheckIsEnforced() {
		User user = userRepository.saveAndFlush(new User());
		assertThatThrownBy(() -> new RefreshToken(user, "d".repeat(64), CREATED, CREATED))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO auth_refresh_token (id, user_id, token_hash, created_at, expires_at) "
						+ "VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), user.getId(), "e".repeat(64), Timestamp.from(EXPIRES),
				Timestamp.from(CREATED)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void consumeAndRevokeTransitionsBehave() {
		User user = userRepository.saveAndFlush(new User());
		RefreshToken token = refreshTokenRepository
				.saveAndFlush(new RefreshToken(user, "f".repeat(64), CREATED, EXPIRES));
		token.consume(NOW, "0".repeat(64));
		refreshTokenRepository.saveAndFlush(token);

		RefreshToken consumed = refreshTokenRepository.findByTokenHash("f".repeat(64)).orElseThrow();
		assertThat(consumed.getRevokedAt()).isEqualTo(NOW);
		assertThat(consumed.getReplacedByTokenHash()).isEqualTo("0".repeat(64));
		assertThatThrownBy(() -> consumed.consume(NOW, "1".repeat(64)))
				.isInstanceOf(IllegalStateException.class);

		RefreshToken live = refreshTokenRepository
				.saveAndFlush(new RefreshToken(user, "2".repeat(64), CREATED, EXPIRES));
		live.revoke(NOW);
		live.revoke(NOW.plusSeconds(60));
		refreshTokenRepository.saveAndFlush(live);
		assertThat(refreshTokenRepository.findByTokenHash("2".repeat(64)).orElseThrow()
				.getRevokedAt()).isEqualTo(NOW);
	}

	@Test
	void atomicConsumeWinsExactlyOnce() {
		User user = userRepository.saveAndFlush(new User());
		refreshTokenRepository.saveAndFlush(new RefreshToken(user, "3".repeat(64), CREATED, EXPIRES));

		assertThat(refreshTokenRepository.consumeToken("3".repeat(64), "4".repeat(64), NOW, NOW))
				.isEqualTo(1);
		assertThat(refreshTokenRepository.consumeToken("3".repeat(64), "5".repeat(64), NOW, NOW))
				.isZero();
		assertThat(refreshTokenRepository.consumeToken("9".repeat(64), "8".repeat(64), NOW, NOW))
				.isZero();
		RefreshToken row = refreshTokenRepository.findByTokenHash("3".repeat(64)).orElseThrow();
		assertThat(row.getRevokedAt()).isEqualTo(NOW);
		assertThat(row.getReplacedByTokenHash()).isEqualTo("4".repeat(64));
	}

	@Test
	void familyRevocationTouchesOnlyLiveRows() {
		User user = userRepository.saveAndFlush(new User());
		User other = userRepository.saveAndFlush(new User());
		refreshTokenRepository.saveAndFlush(new RefreshToken(user, "a1".repeat(32), CREATED, EXPIRES));
		refreshTokenRepository.saveAndFlush(new RefreshToken(user, "b1".repeat(32), CREATED, EXPIRES));
		refreshTokenRepository.saveAndFlush(
				new RefreshToken(user, "c1".repeat(32), EXPIRED_CREATED, EXPIRED_AT));
		refreshTokenRepository.saveAndFlush(new RefreshToken(other, "d1".repeat(32), CREATED, EXPIRES));

		assertThat(refreshTokenRepository.revokeLiveTokensForUser(user.getId(), NOW, NOW)).isEqualTo(2);
		assertThat(refreshTokenRepository.findByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(
				user.getId(), NOW)).isEmpty();
		assertThat(refreshTokenRepository.findByTokenHash("c1".repeat(32)).orElseThrow()
				.getRevokedAt()).isNull();
		assertThat(refreshTokenRepository.findByTokenHash("d1".repeat(32)).orElseThrow()
				.getRevokedAt()).isNull();
	}

	@Test
	void purgeOnlyTargetsExpiredRows() {
		User user = userRepository.saveAndFlush(new User());
		refreshTokenRepository.saveAndFlush(
				new RefreshToken(user, "e1".repeat(32), EXPIRED_CREATED, EXPIRED_AT));
		refreshTokenRepository.saveAndFlush(new RefreshToken(user, "f1".repeat(32), CREATED, EXPIRES));

		assertThat(refreshTokenRepository.purgeExpired(NOW)).isEqualTo(1);
		assertThat(refreshTokenRepository.findByTokenHash("e1".repeat(32))).isEmpty();
		assertThat(refreshTokenRepository.findByTokenHash("f1".repeat(32))).isPresent();
	}

	@Test
	void noRawTokenFieldExists() {
		Set<String> fields = Arrays.stream(RefreshToken.class.getDeclaredFields())
				.map(Field::getName).collect(Collectors.toSet());
		assertThat(fields).containsExactlyInAnyOrder("id", "user", "tokenHash", "createdAt",
				"expiresAt", "revokedAt", "replacedByTokenHash");
		assertThat(Arrays.stream(RefreshToken.class.getMethods()).map(m -> m.getName()))
				.filteredOn(n -> n.startsWith("set")).isEmpty();
		List<String> lifecycle = List.of("revokedAt", "replacedByTokenHash");
		Arrays.stream(RefreshToken.class.getDeclaredFields())
				.filter(f -> f.isAnnotationPresent(Column.class))
				.forEach(f -> assertThat(f.getAnnotation(Column.class).updatable())
						.as("Column %s", f.getName()).isEqualTo(lifecycle.contains(f.getName())));
		Arrays.stream(RefreshToken.class.getDeclaredFields())
				.filter(f -> f.isAnnotationPresent(JoinColumn.class))
				.forEach(f -> assertThat(f.getAnnotation(JoinColumn.class).updatable())
						.as("Join %s", f.getName()).isFalse());
	}

	@Test
	void constructorRejectsMalformedHashes() {
		User user = userRepository.saveAndFlush(new User());
		assertThatThrownBy(() -> new RefreshToken(user, null, CREATED, EXPIRES))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RefreshToken(user, "xyz", CREATED, EXPIRES))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RefreshToken(user, "A".repeat(64), CREATED, EXPIRES))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RefreshToken(null, "a".repeat(64), CREATED, EXPIRES))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
