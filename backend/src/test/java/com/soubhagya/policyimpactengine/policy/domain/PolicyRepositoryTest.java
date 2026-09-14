package com.soubhagya.policyimpactengine.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Repository integration tests against real PostgreSQL (Testcontainers).
 *
 * <p>The schema is created exclusively by the Flyway V1 migration, and the
 * entity mapping is validated by Hibernate at context startup
 * ({@code spring.jpa.hibernate.ddl-auto=validate}); a successful context load
 * below is itself the validation test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PolicyRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private PolicyRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void schemaIsCreatedByFlyway() {
		Integer appliedMigrations = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
				Integer.class);

		assertThat(appliedMigrations).isEqualTo(1);
	}

	@Test
	void policyCanBePersistedAndRetrieved() {
		Policy policy = new Policy("Acme Privacy Policy", "https://example.com/privacy");

		Policy saved = repository.saveAndFlush(policy);
		assertThat(saved.getId()).isNotNull();

		Policy found = repository.findById(saved.getId()).orElseThrow();
		assertThat(found.getName()).isEqualTo("Acme Privacy Policy");
		assertThat(found.getUrl()).isEqualTo("https://example.com/privacy");
		assertThat(found.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
		assertThat(found.getCreatedAt()).isNotNull();
		assertThat(found.getUpdatedAt()).isNotNull();
	}

	@Test
	void policiesCanBeListed() {
		repository.saveAndFlush(new Policy("Acme Privacy Policy", "https://acme.example/privacy"));
		repository.saveAndFlush(new Policy("Globex Terms of Service", "https://globex.example/terms"));

		assertThat(repository.findAll()).hasSize(2);
	}

	@Test
	void policyCanBeDeleted() {
		Policy saved = repository.saveAndFlush(new Policy("Initech Privacy Policy", "https://initech.example/privacy"));

		repository.deleteById(saved.getId());
		repository.flush();

		assertThat(repository.findById(saved.getId())).isEmpty();
	}

	@Test
	void identifiersAreUniqueUuids() {
		Policy first = repository.saveAndFlush(new Policy("First Policy", "https://first.example/privacy"));
		Policy second = repository.saveAndFlush(new Policy("Second Policy", "https://second.example/privacy"));

		assertThat(first.getId()).isNotEqualTo(second.getId());
		assertThat(first.getId()).isInstanceOf(UUID.class);
	}

}
