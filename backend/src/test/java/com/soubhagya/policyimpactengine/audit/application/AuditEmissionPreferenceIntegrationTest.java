package com.soubhagya.policyimpactengine.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditVerificationResult;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.user.EffectiveSensitivityResolver;
import com.soubhagya.policyimpactengine.user.UserPrivacyPreferenceService;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 11C — Testcontainers proof for preference-side emission: one
 * {@code PRIVACY_PREFERENCE_UPSERTED} per created or changed
 * preference and one {@code PRIVACY_PREFERENCE_DELETED} per deleted
 * row, each post-commit. Unchanged rewrites, absent keys, empty
 * updates, and failures stay silent, and concurrent updates never
 * duplicate events.
 */
@SpringBootTest
@Testcontainers
class AuditEmissionPreferenceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private UserPrivacyPreferenceService preferenceService;
	@Autowired private UserRepository userRepository;
	@Autowired private PrivacyConceptRepository conceptRepository;
	@Autowired private UserPrivacyPreferenceRepository preferenceRepository;
	@Autowired private EffectiveSensitivityResolver resolver;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private AuditVerificationService verificationService;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		preferenceRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void bulkCreateEmitsOneEventPerPreference() {
		User user = userRepository.saveAndFlush(new User());

		preferenceService.bulkUpdatePreferences(user.getId(),
				Map.of("LOCATION", 5, "COOKIES", 0));

		List<String> metadata = metadataByType("PRIVACY_PREFERENCE_UPSERTED");
		assertThat(metadata).containsExactlyInAnyOrder(
				"{\"conceptCode\":\"LOCATION\",\"oldSensitivity\":null,\"newSensitivity\":5}",
				"{\"conceptCode\":\"COOKIES\",\"oldSensitivity\":null,\"newSensitivity\":0}");
		assertThat(actorsByType("PRIVACY_PREFERENCE_UPSERTED"))
				.containsExactly(user.getId(), user.getId());
	}

	@Test
	void repeatSameValuesEmitsNothingNew() {
		User user = userRepository.saveAndFlush(new User());
		preferenceService.bulkUpdatePreferences(user.getId(), Map.of("LOCATION", 5));

		preferenceService.bulkUpdatePreferences(user.getId(), Map.of("LOCATION", 5));

		assertThat(countByType("PRIVACY_PREFERENCE_UPSERTED")).isEqualTo(1);
	}

	@Test
	void changeEmitsOneEventWithOldAndNew() {
		User user = userRepository.saveAndFlush(new User());
		preferenceService.bulkUpdatePreferences(user.getId(), Map.of("LOCATION", 5));

		preferenceService.bulkUpdatePreferences(user.getId(), Map.of("LOCATION", 1));

		assertThat(metadataByType("PRIVACY_PREFERENCE_UPSERTED")).containsExactlyInAnyOrder(
				"{\"conceptCode\":\"LOCATION\",\"oldSensitivity\":null,\"newSensitivity\":5}",
				"{\"conceptCode\":\"LOCATION\",\"oldSensitivity\":5,\"newSensitivity\":1}");
	}

	@Test
	void emptyUpdateAndAbsentKeysEmitNothing() {
		User user = userRepository.saveAndFlush(new User());

		preferenceService.bulkUpdatePreferences(user.getId(), Map.of());
		preferenceService.bulkUpdatePreferences(user.getId(), Map.of("LOCATION", 5));

		assertThat(countByType("PRIVACY_PREFERENCE_UPSERTED")).isEqualTo(1);
		assertThat(preferenceService.listPreferences(user.getId())).hasSize(1);
	}

	@Test
	void unknownConceptThrowsAndEmitsNothing() {
		User user = userRepository.saveAndFlush(new User());

		assertThatThrownBy(() -> preferenceService.bulkUpdatePreferences(user.getId(),
				Map.of("BOGUS_CONCEPT", 3))).isInstanceOf(IllegalArgumentException.class);

		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void singleUpsertCreateChangeAndUnchanged() {
		User user = userRepository.saveAndFlush(new User());

		preferenceService.upsertPreference(user.getId(), "LOCATION", 5);
		assertThat(metadataByType("PRIVACY_PREFERENCE_UPSERTED")).containsExactly(
				"{\"conceptCode\":\"LOCATION\",\"oldSensitivity\":null,\"newSensitivity\":5}");

		preferenceService.upsertPreference(user.getId(), "LOCATION", 2);
		assertThat(metadataByType("PRIVACY_PREFERENCE_UPSERTED")).hasSize(2);

		preferenceService.upsertPreference(user.getId(), "LOCATION", 2);
		assertThat(countByType("PRIVACY_PREFERENCE_UPSERTED")).isEqualTo(2);
	}

	@Test
	void deleteExistingEmitsOneEvent() {
		User user = userRepository.saveAndFlush(new User());
		preferenceService.upsertPreference(user.getId(), "LOCATION", 5);

		boolean deleted = preferenceService.deletePreference(user.getId(), "LOCATION");

		assertThat(deleted).isTrue();
		assertThat(metadataByType("PRIVACY_PREFERENCE_DELETED")).containsExactly(
				"{\"conceptCode\":\"LOCATION\",\"oldSensitivity\":5}");
		assertThat(actorsByType("PRIVACY_PREFERENCE_DELETED"))
				.containsExactly(user.getId());
	}

	@Test
	void absentAndRepeatedDeletionEmitNothing() {
		User user = userRepository.saveAndFlush(new User());

		assertThat(preferenceService.deletePreference(user.getId(), "LOCATION")).isFalse();
		preferenceService.upsertPreference(user.getId(), "LOCATION", 5);
		assertThat(preferenceService.deletePreference(user.getId(), "LOCATION")).isTrue();
		assertThat(preferenceService.deletePreference(user.getId(), "LOCATION")).isFalse();

		assertThat(countByType("PRIVACY_PREFERENCE_DELETED")).isEqualTo(1);
	}

	@Test
	void concurrentUpdatesDoNotDuplicateEvents() throws Exception {
		User user = userRepository.saveAndFlush(new User());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<?> first = executor.submit(() -> {
				start.await();
				preferenceService.bulkUpdatePreferences(user.getId(), Map.of("LOCATION", 4));
				return null;
			});
			Future<?> second = executor.submit(() -> {
				start.await();
				preferenceService.bulkUpdatePreferences(user.getId(), Map.of("LOCATION", 5));
				return null;
			});
			start.countDown();
			first.get(60, TimeUnit.SECONDS);
			second.get(60, TimeUnit.SECONDS);

			assertThat(countByType("PRIVACY_PREFERENCE_UPSERTED")).isEqualTo(2);
			assertThat(preferenceService.effectiveSensitivities(user.getId())
					.get("LOCATION")).isIn(4, 5);
			assertThat(verificationService.verify().isValid()).isTrue();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void auditFailureDoesNotRollBackPreferences() {
		User user = userRepository.saveAndFlush(new User());
		AuditService failingAudit = mock(AuditService.class);
		doThrow(new AuditAppendException("forced audit failure", null))
				.when(failingAudit).append(any(), any(), any(), any(), any(), any());
		UserPrivacyPreferenceService failingService = new UserPrivacyPreferenceService(
				userRepository, conceptRepository, preferenceRepository, resolver,
				transactionManager, failingAudit);

		assertThatThrownBy(() -> failingService.bulkUpdatePreferences(user.getId(),
				Map.of("LOCATION", 5))).isInstanceOf(AuditAppendException.class);

		assertThat(preferenceService.effectiveSensitivities(user.getId())
				.get("LOCATION")).isEqualTo(5);
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void mixedOperationsLeaveChainValidWithExactMetadata() {
		User user = userRepository.saveAndFlush(new User());
		preferenceService.bulkUpdatePreferences(user.getId(),
				Map.of("LOCATION", 5, "COOKIES", 0));
		preferenceService.bulkUpdatePreferences(user.getId(), Map.of("LOCATION", 5));
		preferenceService.deletePreference(user.getId(), "COOKIES");

		AuditVerificationResult result = verificationService.verify();
		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(3);
		assertThat(metadataByType("PRIVACY_PREFERENCE_DELETED")).containsExactly(
				"{\"conceptCode\":\"COOKIES\",\"oldSensitivity\":0}");
		List<String> allMetadata = jdbcTemplate.queryForList(
				"SELECT metadata FROM audit_event", String.class);
		assertThat(allMetadata).noneMatch(value -> value != null
				&& value.toLowerCase().matches(
						"(?s).*(password|token|authorization|secret|email|url|content).*"));
	}

	private int countByType(String eventType) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ?",
				Integer.class, eventType);
		return count == null ? 0 : count;
	}

	private List<String> metadataByType(String eventType) {
		return jdbcTemplate.queryForList("SELECT metadata FROM audit_event "
				+ "WHERE event_type = ? ORDER BY occurred_at, id", String.class, eventType);
	}

	private List<Object> actorsByType(String eventType) {
		return jdbcTemplate.queryForList("SELECT actor_user_id FROM audit_event "
				+ "WHERE event_type = ? ORDER BY occurred_at, id", Object.class, eventType);
	}
}
