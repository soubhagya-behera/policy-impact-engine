package com.soubhagya.policyimpactengine.diff.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

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

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;

/**
 * Phase 2K — repository tests for persisted policy changes against real
 * PostgreSQL (Testcontainers).
 *
 * <p>The schema is created exclusively by the Flyway V1/V2/V3 migrations,
 * and the entity mapping is validated by Hibernate at context startup
 * ({@code spring.jpa.hibernate.ddl-auto=validate}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PolicyChangeRecordRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private PolicyChangeRecordRepository changeRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void cleanDatabase() {
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void schemaIsCreatedByFlywayV3() {
		Integer appliedMigrations = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success = true",
				Integer.class);

		assertThat(appliedMigrations).isEqualTo(1);
	}

	@Test
	void persistAddedChange() {
		Transition transition = transition("line one\nline two", "line one\nline two\nline three");

		PolicyChangeRecord saved = changeRepository.saveAndFlush(new PolicyChangeRecord(
				transition.previous(), transition.next(), PolicyChangeType.ADDED, null, "line three", 0));

		assertThat(saved.getId()).isNotNull();
		PolicyChangeRecord found = changeRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getChangeType()).isEqualTo(PolicyChangeType.ADDED);
		assertThat(found.getNewText()).isEqualTo("line three");
	}

	@Test
	void persistRemovedChange() {
		Transition transition = transition("line one\nline two", "line one");

		PolicyChangeRecord saved = changeRepository.saveAndFlush(new PolicyChangeRecord(
				transition.previous(), transition.next(), PolicyChangeType.REMOVED, "line two", null, 0));

		PolicyChangeRecord found = changeRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getChangeType()).isEqualTo(PolicyChangeType.REMOVED);
		assertThat(found.getOldText()).isEqualTo("line two");
	}

	@Test
	void persistModifiedChange() {
		Transition transition = transition("old wording", "new wording");

		PolicyChangeRecord saved = changeRepository.saveAndFlush(new PolicyChangeRecord(
				transition.previous(), transition.next(), PolicyChangeType.MODIFIED, "old wording", "new wording",
				0));

		PolicyChangeRecord found = changeRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getChangeType()).isEqualTo(PolicyChangeType.MODIFIED);
		assertThat(found.getOldText()).isEqualTo("old wording");
		assertThat(found.getNewText()).isEqualTo("new wording");
	}

	@Test
	void oldTextIsNullForAdded() {
		Transition transition = transition("a", "a\nb");

		PolicyChangeRecord saved = changeRepository.saveAndFlush(new PolicyChangeRecord(
				transition.previous(), transition.next(), PolicyChangeType.ADDED, null, "b", 0));

		assertThat(changeRepository.findById(saved.getId()).orElseThrow().getOldText()).isNull();
	}

	@Test
	void newTextIsNullForRemoved() {
		Transition transition = transition("a\nb", "a");

		PolicyChangeRecord saved = changeRepository.saveAndFlush(new PolicyChangeRecord(
				transition.previous(), transition.next(), PolicyChangeType.REMOVED, "b", null, 0));

		assertThat(changeRepository.findById(saved.getId()).orElseThrow().getNewText()).isNull();
	}

	@Test
	void modifiedHasBothOldAndNewText() {
		Transition transition = transition("before", "after");

		PolicyChangeRecord saved = changeRepository.saveAndFlush(new PolicyChangeRecord(
				transition.previous(), transition.next(), PolicyChangeType.MODIFIED, "before", "after", 0));

		PolicyChangeRecord found = changeRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getOldText()).isEqualTo("before");
		assertThat(found.getNewText()).isEqualTo("after");
	}

	@Test
	void changesAreRetrievedInDeterministicDocumentOrder() {
		Transition transition = transition("a\nb\nc", "x\na\nc\nd");

		// Persisted out of order on purpose; retrieval must still be ordered.
		changeRepository.saveAll(List.of(
				new PolicyChangeRecord(transition.previous(), transition.next(), PolicyChangeType.ADDED, null,
						"d", 2),
				new PolicyChangeRecord(transition.previous(), transition.next(), PolicyChangeType.MODIFIED,
						"placeholder-old", "placeholder-new", 1),
				new PolicyChangeRecord(transition.previous(), transition.next(), PolicyChangeType.ADDED, null,
						"x", 0)));
		changeRepository.flush();

		List<PolicyChangeRecord> found = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(transition.next().getId());

		assertThat(found).extracting(PolicyChangeRecord::getChangeOrder).containsExactly(0, 1, 2);
		assertThat(found).extracting(PolicyChangeRecord::getNewText).containsExactly("x", "placeholder-new",
				"d");
	}

	@Test
	void changesBelongToCorrectPreviousAndNewVersionTransition() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme Privacy Policy", "https://example.com/privacy"));
		PolicyVersion v1 = versionRepository
				.saveAndFlush(new PolicyVersion(policy, 1, "first", hasher.hash("first")));
		PolicyVersion v2 = versionRepository
				.saveAndFlush(new PolicyVersion(policy, 2, "second", hasher.hash("second")));
		PolicyVersion v3 = versionRepository
				.saveAndFlush(new PolicyVersion(policy, 3, "third", hasher.hash("third")));
		changeRepository.saveAndFlush(new PolicyChangeRecord(v1, v2, PolicyChangeType.MODIFIED, "first",
				"second", 0));
		changeRepository.saveAndFlush(new PolicyChangeRecord(v2, v3, PolicyChangeType.MODIFIED, "second",
				"third", 0));

		List<PolicyChangeRecord> forV2 = changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v2.getId());
		List<PolicyChangeRecord> forV3 = changeRepository.findByNewVersion_IdOrderByChangeOrderAsc(v3.getId());

		assertThat(forV2).hasSize(1);
		assertThat(forV2.get(0).getPreviousVersion().getId()).isEqualTo(v1.getId());
		assertThat(forV2.get(0).getNewVersion().getId()).isEqualTo(v2.getId());
		assertThat(forV2.get(0).getPreviousVersion().getPolicy().getId()).isEqualTo(policy.getId());

		assertThat(forV3).hasSize(1);
		assertThat(forV3.get(0).getPreviousVersion().getId()).isEqualTo(v2.getId());
		assertThat(forV3.get(0).getNewVersion().getId()).isEqualTo(v3.getId());
	}

	@Test
	void multiplePoliciesMaintainIndependentChangeHistories() {
		Policy first = policyRepository
				.saveAndFlush(new Policy("First Policy", "https://first.example/privacy"));
		Policy second = policyRepository
				.saveAndFlush(new Policy("Second Policy", "https://second.example/privacy"));
		PolicyVersion firstV1 = versionRepository
				.saveAndFlush(new PolicyVersion(first, 1, "shared", hasher.hash("shared")));
		PolicyVersion firstV2 = versionRepository
				.saveAndFlush(new PolicyVersion(first, 2, "first changed", hasher.hash("first changed")));
		PolicyVersion secondV1 = versionRepository
				.saveAndFlush(new PolicyVersion(second, 1, "shared", hasher.hash("shared")));
		PolicyVersion secondV2 = versionRepository
				.saveAndFlush(new PolicyVersion(second, 2, "second changed", hasher.hash("second changed")));
		changeRepository.saveAndFlush(new PolicyChangeRecord(firstV1, firstV2, PolicyChangeType.MODIFIED,
				"shared", "first changed", 0));
		changeRepository.saveAndFlush(new PolicyChangeRecord(secondV1, secondV2, PolicyChangeType.MODIFIED,
				"shared", "second changed", 0));

		List<PolicyChangeRecord> firstChanges = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(firstV2.getId());
		List<PolicyChangeRecord> secondChanges = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(secondV2.getId());

		assertThat(firstChanges).hasSize(1);
		assertThat(firstChanges.get(0).getNewText()).isEqualTo("first changed");
		assertThat(secondChanges).hasSize(1);
		assertThat(secondChanges.get(0).getNewText()).isEqualTo("second changed");
	}

	@Test
	void duplicateChangeOrderForSameNewVersionIsRejected() {
		Transition transition = transition("a", "b");
		changeRepository.saveAndFlush(new PolicyChangeRecord(transition.previous(), transition.next(),
				PolicyChangeType.MODIFIED, "a", "b", 0));

		assertThatThrownBy(() -> changeRepository.saveAndFlush(new PolicyChangeRecord(transition.previous(),
				transition.next(), PolicyChangeType.ADDED, null, "extra", 0)))
						.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void invalidNullShapeIsRejected() {
		Transition transition = transition("a", "b");

		assertThatThrownBy(() -> new PolicyChangeRecord(transition.previous(), transition.next(),
				PolicyChangeType.ADDED, "must be null", "b", 0))
						.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyChangeRecord(transition.previous(), transition.next(),
				PolicyChangeType.REMOVED, "a", "must be null", 0))
						.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyChangeRecord(transition.previous(), transition.next(),
				PolicyChangeType.MODIFIED, "same", "same", 0))
						.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void changeRecordIsEffectivelyImmutableThroughApplicationApi() {
		List<String> setters = Arrays.stream(PolicyChangeRecord.class.getMethods())
				.map(Method::getName)
				.filter(name -> name.startsWith("set"))
				.toList();
		assertThat(setters).isEmpty();

		List<String> repositoryOperations = Arrays.stream(PolicyChangeRecordRepository.class.getMethods())
				.map(Method::getName)
				.toList();
		assertThat(repositoryOperations).contains("findByNewVersion_IdOrderByChangeOrderAsc");
		assertThat(repositoryOperations).doesNotContain("update", "edit");

		Arrays.stream(PolicyChangeRecord.class.getDeclaredFields())
				.filter(field -> field.isAnnotationPresent(Column.class))
				.forEach(field -> assertThat(field.getAnnotation(Column.class).updatable())
						.as("Column %s must be immutable", field.getName())
						.isFalse());
		Arrays.stream(PolicyChangeRecord.class.getDeclaredFields())
				.filter(field -> field.isAnnotationPresent(JoinColumn.class))
				.forEach(field -> assertThat(field.getAnnotation(JoinColumn.class).updatable())
						.as("Join column %s must be immutable", field.getName())
						.isFalse());
	}

	private record Transition(PolicyVersion previous, PolicyVersion next) {
	}

	private Transition transition(String previousContent, String newContent) {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("Policy " + System.nanoTime(), "https://example.com/" + System.nanoTime()));
		PolicyVersion v1 = versionRepository
				.saveAndFlush(new PolicyVersion(policy, 1, previousContent, hasher.hash(previousContent)));
		PolicyVersion v2 = versionRepository
				.saveAndFlush(new PolicyVersion(policy, 2, newContent, hasher.hash(newContent)));
		return new Transition(v1, v2);
	}

}
