package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;

/**
 * Phase 2G — behavior tests for immutable policy-version persistence and
 * exact hash-based change detection against real PostgreSQL
 * (Testcontainers).
 *
 * <p>Exercises the real application layers together: Service → Repositories
 * → PostgreSQL (Flyway-migrated, Hibernate-validated). Hashes are produced
 * with the production {@link Sha256PolicyContentHasher}; the service itself
 * never computes hashes.
 *
 * <p>Test methods are deliberately not wrapped in a single transaction: each
 * {@code observe} call commits in its own transaction, so assertions below
 * verify real transaction boundaries.
 */
@SpringBootTest
@Testcontainers
class PolicyVersionServiceTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	private final PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@Autowired
	private PolicyVersionService service;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@BeforeEach
	void cleanDatabase() {
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void firstObservationCreatesVersionOne() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");

		PolicyVersionObservation observation = service.observe(policy.getId(), "canonical content",
				hasher.hash("canonical content"));

		assertThat(observation.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(observation.version().getVersionNumber()).isEqualTo(1);
		assertThat(observation.version().getId()).isNotNull();
		assertThat(versionRepository.count()).isEqualTo(1);
	}

	@Test
	void firstObservationPersistsSuppliedNormalizedContentAndHash() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		String content = "canonical normalized content";
		String hash = hasher.hash(content);

		PolicyVersionObservation observation = service.observe(policy.getId(), content, hash);

		PolicyVersion persisted = versionRepository.findById(observation.version().getId()).orElseThrow();
		assertThat(persisted.getNormalizedContent()).isEqualTo(content);
		assertThat(persisted.getContentHash()).isEqualTo(hash);
		assertThat(persisted.getVersionNumber()).isEqualTo(1);
		assertThat(persisted.getObservedAt()).isNotNull();
	}

	@Test
	void secondObservationWithIdenticalHashDoesNotCreateSecondVersion() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		String content = "canonical content";
		service.observe(policy.getId(), content, hasher.hash(content));

		service.observe(policy.getId(), content, hasher.hash(content));

		assertThat(versionRepository.count()).isEqualTo(1);
		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId())).hasSize(1);
	}

	@Test
	void identicalContentHashReturnsUnchanged() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		String content = "canonical content";
		PolicyVersionObservation first = service.observe(policy.getId(), content, hasher.hash(content));

		PolicyVersionObservation second = service.observe(policy.getId(), content, hasher.hash(content));

		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(second.version().getId()).isEqualTo(first.version().getId());
		assertThat(second.version().getVersionNumber()).isEqualTo(1);
	}

	@Test
	void differentHashCreatesVersionTwo() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		service.observe(policy.getId(), "first content", hasher.hash("first content"));

		PolicyVersionObservation observation = service.observe(policy.getId(), "second content",
				hasher.hash("second content"));

		assertThat(observation.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(versionRepository.count()).isEqualTo(2);
	}

	@Test
	void versionTwoReceivesNextSequenceNumber() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		service.observe(policy.getId(), "first content", hasher.hash("first content"));

		PolicyVersionObservation observation = service.observe(policy.getId(), "second content",
				hasher.hash("second content"));

		assertThat(observation.version().getVersionNumber()).isEqualTo(2);
		assertThat(observation.version().getNormalizedContent()).isEqualTo("second content");
		assertThat(observation.version().getContentHash()).isEqualTo(hasher.hash("second content"));
	}

	@Test
	void versionOneRemainsUnchangedAfterVersionTwoIsCreated() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		String firstContent = "first content";
		String firstHash = hasher.hash(firstContent);
		UUID versionOneId = service.observe(policy.getId(), firstContent, firstHash).version().getId();
		Instant observedAt = versionRepository.findById(versionOneId).orElseThrow().getObservedAt();

		service.observe(policy.getId(), "second content", hasher.hash("second content"));

		PolicyVersion versionOne = versionRepository.findById(versionOneId).orElseThrow();
		assertThat(versionOne.getVersionNumber()).isEqualTo(1);
		assertThat(versionOne.getNormalizedContent()).isEqualTo(firstContent);
		assertThat(versionOne.getContentHash()).isEqualTo(firstHash);
		assertThat(versionOne.getObservedAt()).isEqualTo(observedAt);
	}

	@Test
	void thirdChangedObservationCreatesVersionThree() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		service.observe(policy.getId(), "first content", hasher.hash("first content"));
		service.observe(policy.getId(), "second content", hasher.hash("second content"));

		PolicyVersionObservation observation = service.observe(policy.getId(), "third content",
				hasher.hash("third content"));

		assertThat(observation.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(observation.version().getVersionNumber()).isEqualTo(3);
		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId()))
				.extracting(PolicyVersion::getVersionNumber)
				.containsExactly(1, 2, 3);
	}

	@Test
	void multiplePoliciesMaintainIndependentVersionSequences() {
		Policy first = registeredPolicy("First Policy", "https://first.example/privacy");
		Policy second = registeredPolicy("Second Policy", "https://second.example/privacy");

		PolicyVersionObservation firstV1 = service.observe(first.getId(), "shared content",
				hasher.hash("shared content"));
		PolicyVersionObservation secondV1 = service.observe(second.getId(), "shared content",
				hasher.hash("shared content"));

		assertThat(firstV1.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(secondV1.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);

		PolicyVersionObservation firstV2 = service.observe(first.getId(), "changed content",
				hasher.hash("changed content"));

		assertThat(firstV2.version().getVersionNumber()).isEqualTo(2);
		assertThat(versionRepository.findTopByPolicy_IdOrderByVersionNumberDesc(second.getId()).orElseThrow()
				.getVersionNumber()).isEqualTo(1);
		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(first.getId())).hasSize(2);
		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(second.getId())).hasSize(1);
	}

	@Test
	void latestVersionLookupReturnsCorrectCurrentVersion() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		service.observe(policy.getId(), "first content", hasher.hash("first content"));
		service.observe(policy.getId(), "second content", hasher.hash("second content"));
		PolicyVersionObservation third = service.observe(policy.getId(), "third content",
				hasher.hash("third content"));

		PolicyVersion latest = versionRepository.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId())
				.orElseThrow();

		assertThat(latest.getId()).isEqualTo(third.version().getId());
		assertThat(latest.getVersionNumber()).isEqualTo(3);
		assertThat(latest.getNormalizedContent()).isEqualTo("third content");
	}

	@Test
	void databaseUniqueConstraintPreventsDuplicateVersionNumbersForSamePolicy() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		versionRepository.saveAndFlush(new PolicyVersion(policy, 1, "first", hasher.hash("first")));

		assertThatThrownBy(() -> versionRepository
				.saveAndFlush(new PolicyVersion(policy, 1, "duplicate", hasher.hash("duplicate"))))
						.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policy.getId())).hasSize(1);
	}

	@Test
	void versionIsEffectivelyImmutableThroughApplicationService() {
		List<String> setters = Arrays.stream(PolicyVersion.class.getMethods())
				.map(Method::getName)
				.filter(name -> name.startsWith("set"))
				.toList();
		assertThat(setters).isEmpty();

		List<String> serviceOperations = Arrays.stream(PolicyVersionService.class.getMethods())
				.map(Method::getName)
				.toList();
		assertThat(serviceOperations).contains("observe");
		assertThat(serviceOperations).doesNotContain("update", "save", "delete", "deleteById", "edit");

		Arrays.stream(PolicyVersion.class.getDeclaredFields())
				.filter(field -> field.isAnnotationPresent(Column.class))
				.forEach(field -> assertThat(field.getAnnotation(Column.class).updatable())
						.as("Column %s must be immutable", field.getName())
						.isFalse());
		Arrays.stream(PolicyVersion.class.getDeclaredFields())
				.filter(field -> field.isAnnotationPresent(JoinColumn.class))
				.forEach(field -> assertThat(field.getAnnotation(JoinColumn.class).updatable())
						.as("Join column %s must be immutable", field.getName())
						.isFalse());
	}

	@Test
	void eachObservationCommitsInItsOwnTransaction() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");

		service.observe(policy.getId(), "first content", hasher.hash("first content"));
		assertThat(versionRepository.count()).isEqualTo(1);

		service.observe(policy.getId(), "second content", hasher.hash("second content"));
		assertThat(versionRepository.count()).isEqualTo(2);

		// A read-only lookup in a separate transaction sees everything committed so far.
		assertThat(versionRepository.findTopByPolicy_IdOrderByVersionNumberDesc(policy.getId()).orElseThrow()
				.getVersionNumber()).isEqualTo(2);
	}

	@Test
	void failedObservationsPersistNothing() {
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");

		assertThatThrownBy(() -> service.observe(UUID.randomUUID(), "content", hasher.hash("content")))
				.isInstanceOf(NoSuchElementException.class);
		assertThatThrownBy(() -> service.observe(policy.getId(), "content", "  "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.observe(policy.getId(), null, hasher.hash("content")))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(versionRepository.count()).isZero();
	}

	private Policy registeredPolicy(String name, String url) {
		return policyRepository.saveAndFlush(new Policy(name, url));
	}

}
