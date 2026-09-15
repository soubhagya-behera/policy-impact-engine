package com.soubhagya.policyimpactengine.policy.domain;

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

import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

/**
 * Repository integration tests for {@link PolicyVersion} against real
 * PostgreSQL (Testcontainers).
 *
 * <p>The schema is created exclusively by the Flyway V1/V2 migrations, and
 * the entity mapping is validated by Hibernate at context startup
 * ({@code spring.jpa.hibernate.ddl-auto=validate}); a successful context
 * load is itself the validation test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PolicyVersionRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDatabase() {
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void schemaIsCreatedByFlywayV2() {
		Integer appliedMigrations = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success = true",
				Integer.class);

		assertThat(appliedMigrations).isEqualTo(1);
	}

	@Test
	void versionCanBePersistedAndRetrieved() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		String content = "canonical normalized content";
		String hash = hasher.hash(content);

		PolicyVersion saved = versionRepository.saveAndFlush(new PolicyVersion(policy, 1, content, hash));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getObservedAt()).isNotNull();

		PolicyVersion found = versionRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getVersionNumber()).isEqualTo(1);
		assertThat(found.getNormalizedContent()).isEqualTo(content);
		assertThat(found.getContentHash()).isEqualTo(hash);
		assertThat(found.getPolicy().getId()).isEqualTo(policy.getId());
	}

	@Test
	void latestVersionLookupReturnsHighestNumberedVersion() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		versionRepository.saveAndFlush(new PolicyVersion(policy, 1, "first", hasher.hash("first")));
		versionRepository.saveAndFlush(new PolicyVersion(policy, 2, "second", hasher.hash("second")));
		versionRepository.saveAndFlush(new PolicyVersion(policy, 3, "third", hasher.hash("third")));

		PolicyVersion latest = versionRepository.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId())
				.orElseThrow();

		assertThat(latest.getVersionNumber()).isEqualTo(3);
		assertThat(latest.getNormalizedContent()).isEqualTo("third");
	}

	@Test
	void versionsAreListedInSequenceOrder() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		versionRepository.saveAndFlush(new PolicyVersion(policy, 2, "second", hasher.hash("second")));
		versionRepository.saveAndFlush(new PolicyVersion(policy, 1, "first", hasher.hash("first")));

		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId()))
				.extracting(PolicyVersion::getVersionNumber)
				.containsExactly(1, 2);
	}

	@Test
	void latestVersionLookupIsEmptyWhenNoVersionsExist() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));

		assertThat(versionRepository.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId())).isEmpty();
	}

	@Test
	void uniqueConstraintPreventsDuplicateVersionNumbersForSamePolicy() {
		Policy policy = policyRepository.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		versionRepository.saveAndFlush(new PolicyVersion(policy, 1, "first", hasher.hash("first")));

		assertThatThrownBy(() -> versionRepository
				.saveAndFlush(new PolicyVersion(policy, 1, "duplicate", hasher.hash("duplicate"))))
						.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sameVersionNumberIsAllowedForDifferentPolicies() {
		Policy first = policyRepository.saveAndFlush(new Policy("First Policy", "https://first.example/privacy"));
		Policy second = policyRepository.saveAndFlush(new Policy("Second Policy", "https://second.example/privacy"));
		versionRepository.saveAndFlush(new PolicyVersion(first, 1, "content", hasher.hash("content")));

		PolicyVersion other = versionRepository
				.saveAndFlush(new PolicyVersion(second, 1, "content", hasher.hash("content")));

		assertThat(other.getVersionNumber()).isEqualTo(1);
	}

}
