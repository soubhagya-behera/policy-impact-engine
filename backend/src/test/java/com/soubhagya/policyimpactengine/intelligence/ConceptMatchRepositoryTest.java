package com.soubhagya.policyimpactengine.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

/**
 * Phase 2N — repository tests for the DB-backed vocabulary and match persistence.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ConceptMatchRepositoryTest {

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
	private PrivacyConceptRepository conceptRepository;
	@Autowired
	private ChangeConceptMatchRepository matchRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void clean() {
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
	}

	@Test
	void flywayV4SeededNineConcepts() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success = true", Integer.class);
		assertThat(applied).isEqualTo(1);
		assertThat(conceptRepository.count()).isEqualTo(9);
		assertThat(conceptRepository.findByCode("LOCATION")).isPresent();
		assertThat(conceptRepository.findByCode("THIRD_PARTY_SHARING")).isPresent();
		assertThat(conceptRepository.findByCode("ADVERTISING")).isPresent();
	}

	@Test
	void persistAndRetrieveMatchInConceptCodeOrder() {
		PrivacyConcept location = conceptRepository.findByCode("LOCATION").orElseThrow();
		PrivacyConcept advertising = conceptRepository.findByCode("ADVERTISING").orElseThrow();
		PolicyChangeRecord change = persistedChange("old", "Location and advertising");

		// Save out of code order; retrieval must be ordered by concept code.
		matchRepository.saveAll(List.of(
				new ChangeConceptMatch(change, advertising, "advertising", "ADVERTISING:advertising", "KEYWORD"),
				new ChangeConceptMatch(change, location, "Location", "LOCATION:location", "KEYWORD")));
		matchRepository.flush();

		List<ChangeConceptMatch> found = matchRepository.findByChange_IdOrderByConcept_CodeAsc(change.getId());
		assertThat(found).hasSize(2);
		assertThat(found).extracting(m -> m.getConcept().getCode()).containsExactly("ADVERTISING", "LOCATION");
	}

	@Test
	void duplicateConceptPerChangeIsRejected() {
		PrivacyConcept location = conceptRepository.findByCode("LOCATION").orElseThrow();
		PolicyChangeRecord change = persistedChange("old", "location data");
		matchRepository.saveAndFlush(new ChangeConceptMatch(change, location, "location", "LOCATION:location", "KEYWORD"));
		// Second row same change+concept must violate unique constraint
		try {
			matchRepository.saveAndFlush(new ChangeConceptMatch(change, location, "location", "LOCATION:location", "KEYWORD"));
			jdbcTemplate.execute("SELECT 1"); // force flush
			assertThat(false).as("Duplicate should have thrown").isTrue();
		} catch (Exception ex) {
			assertThat(ex.getMessage() + " cause:" + ex.getCause()).containsIgnoringCase("unique");
		}
	}

	private PolicyChangeRecord persistedChange(String oldText, String newText) {
		Policy policy = policyRepository.saveAndFlush(new Policy("P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		PolicyVersion v1 = versionRepository.saveAndFlush(new PolicyVersion(policy, 1, oldText, hasher.hash(oldText)));
		PolicyVersion v2 = versionRepository.saveAndFlush(new PolicyVersion(policy, 2, newText, hasher.hash(newText)));
		return changeRepository.saveAndFlush(new PolicyChangeRecord(v1, v2, PolicyChangeType.MODIFIED, oldText, newText, 0));
	}
}
