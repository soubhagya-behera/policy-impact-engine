package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 8A — Testcontainers repository tests for the V16 credential
 * transition: migration applies, normalized-email lookup works,
 * duplicates violate the unique index, and legacy credential-less rows
 * coexist with credentialed rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserAuthRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		userRepository.deleteAll();
	}

	@Test
	void v16MigrationApplies() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version='16' AND success=true",
				Integer.class);
		assertThat(applied).isEqualTo(1);
	}

	@Test
	void credentialedUserPersistsAndResolvesByEmail() {
		User user = new User();
		user.assignCredentials("user@example.com", "$2a$10$abcdefghijklmnopqrstuu");
		User saved = userRepository.saveAndFlush(user);

		assertThat(userRepository.findByEmail("user@example.com"))
				.isPresent()
				.get()
				.extracting(User::getId)
				.isEqualTo(saved.getId());
		assertThat(userRepository.findByEmail("unknown@example.com")).isEmpty();
	}

	@Test
	void duplicateEmailViolatesUniqueIndex() {
		User first = new User();
		first.assignCredentials("user@example.com", "$2a$10$abcdefghijklmnopqrstuu");
		userRepository.saveAndFlush(first);

		User second = new User();
		second.assignCredentials("user@example.com", "$2a$10$abcdefghijklmnopqrstuu");

		assertThatThrownBy(() -> userRepository.saveAndFlush(second))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void legacyCredentialLessRowsCoexistWithCredentialedRows() {
		userRepository.saveAndFlush(new User());

		User credentialed = new User();
		credentialed.assignCredentials("user@example.com", "$2a$10$abcdefghijklmnopqrstuu");
		userRepository.saveAndFlush(credentialed);

		assertThat(userRepository.count()).isEqualTo(2);
	}
}
