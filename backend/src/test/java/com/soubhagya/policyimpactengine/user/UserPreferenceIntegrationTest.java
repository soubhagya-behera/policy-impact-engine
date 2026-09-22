package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 2P — integration for user isolation and fallback.
 */
@SpringBootTest
@Testcontainers
class UserPreferenceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private UserRepository userRepository;
	@Autowired private PrivacyConceptRepository conceptRepository;
	@Autowired private UserPrivacyPreferenceRepository prefRepository;
	@Autowired private UserService userService;
	@Autowired private UserPrivacyPreferenceService prefService;

	// Phase 11C: preference upserts/deletes emit audit rows referencing
	// users, so audit rows go first or user deletion violates the actor
	// foreign key.
	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		prefRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void isolationAndFallbackAndUpdateAndDelete() {
		User a = userService.createUser();
		User b = userService.createUser();

		// Initially defaults
		Map<String, Integer> defaultsA = prefService.effectiveSensitivities(a.getId());
		Map<String, Integer> defaultsB = prefService.effectiveSensitivities(b.getId());
		// LOCATION default is 3 per V4 seed, COOKIES 2
		assertThat(defaultsA.get("LOCATION")).isEqualTo(3);
		assertThat(defaultsB.get("LOCATION")).isEqualTo(3);
		assertThat(defaultsA.get("COOKIES")).isEqualTo(2);
		assertThat(defaultsB.get("COOKIES")).isEqualTo(2);

		// User A sets LOCATION=5, COOKIES=0
		prefService.upsertPreference(a.getId(), "LOCATION", 5);
		prefService.upsertPreference(a.getId(), "COOKIES", 0);
		Map<String, Integer> afterA = prefService.effectiveSensitivities(a.getId());
		Map<String, Integer> afterB = prefService.effectiveSensitivities(b.getId());
		assertThat(afterA.get("LOCATION")).isEqualTo(5);
		assertThat(afterA.get("COOKIES")).isZero();
		// other concepts still default
		assertThat(afterA.get("ADVERTISING")).isEqualTo(conceptRepository.findByCode("ADVERTISING").orElseThrow().getDefaultSensitivity());
		assertThat(afterB.get("LOCATION")).isEqualTo(3);
		assertThat(afterB.get("COOKIES")).isEqualTo(2);

		// Update LOCATION to 1, no duplicate row
		prefService.upsertPreference(a.getId(), "LOCATION", 1);
		assertThat(prefService.listPreferences(a.getId())).filteredOn(p -> p.getConcept().getCode().equals("LOCATION")).hasSize(1);
		assertThat(prefService.effectiveSensitivities(a.getId()).get("LOCATION")).isEqualTo(1);

		// Delete LOCATION -> fallback
		prefService.deletePreference(a.getId(), "LOCATION");
		assertThat(prefService.effectiveSensitivities(a.getId()).get("LOCATION")).isEqualTo(3);
		// COOKIES still 0
		assertThat(prefService.effectiveSensitivities(a.getId()).get("COOKIES")).isZero();

		// No cross-user leakage: B still defaults
		assertThat(prefService.listPreferences(b.getId())).isEmpty();
		assertThat(prefService.effectiveSensitivities(b.getId()).get("LOCATION")).isEqualTo(3);
	}
}
