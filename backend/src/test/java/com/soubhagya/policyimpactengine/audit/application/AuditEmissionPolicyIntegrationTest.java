package com.soubhagya.policyimpactengine.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicySimHash;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.JsoupPolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.policy.web.PolicyController;
import com.soubhagya.policyimpactengine.policy.web.dto.CreatePolicyRequest;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyResponse;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;

/**
 * Phase 11C — Testcontainers proof for policy-side emission: one
 * {@code POLICY_REGISTERED} per controller registration and one
 * {@code POLICY_OWNER_ASSIGNED} per actual null-to-user assignment.
 * Reads, failed registrations, observations, same-owner no-ops, and
 * rejections stay silent, and an audit failure never rolls back a
 * committed assignment.
 */
@SpringBootTest
@Testcontainers
class AuditEmissionPolicyIntegrationTest {

	private static final String HTML = "<html><body><h1>Privacy Policy</h1>"
			+ "<p>We collect your location data.</p></body></html>";
	private static final String REFORMATTED = "<html><body><h1>  Privacy   Policy </h1>"
			+ "<p>We collect your location data.</p></body></html>";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private PolicyController policyController;
	@Autowired private PolicyService policyService;
	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyVersionService versionService;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private PolicyChangeRecordRepository changeRepository;
	@Autowired private PolicyFetchAttemptService attemptService;
	@Autowired private PolicyFetchAttemptRepository attemptRepository;
	@Autowired private PolicyDiffEngine diffEngine;
	@Autowired private PolicySimHash simHash;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private UserRepository userRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		attemptRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void policyRegistrationEmitsOneEvent() {
		User owner = userRepository.saveAndFlush(new User());
		PolicyResponse response = policyController.register(authentication(owner.getId()),
				new CreatePolicyRequest("Acme", "https://example.com/privacy")).getBody();

		List<Map<String, Object>> rows = rowsByType("POLICY_REGISTERED");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("actor_user_id")).isEqualTo(owner.getId());
		assertThat(rows.get(0).get("resource_type")).isEqualTo("POLICY");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(response.id());
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");
	}

	@Test
	void readsEmitNothing() {
		User owner = userRepository.saveAndFlush(new User());
		PolicyResponse created = policyController.register(authentication(owner.getId()),
				new CreatePolicyRequest("Acme", "https://example.com/privacy")).getBody();

		policyController.getById(authentication(owner.getId()), created.id());
		policyController.list(authentication(owner.getId()));

		assertThat(countByType("POLICY_REGISTERED")).isEqualTo(1);
		assertThat(auditEventRepository.count()).isEqualTo(1);
	}

	@Test
	void failedRegistrationEmitsNothing() {
		User owner = userRepository.saveAndFlush(new User());

		assertThatThrownBy(() -> policyController.register(authentication(owner.getId()),
				new CreatePolicyRequest("Acme", "not-a-url")))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(auditEventRepository.count()).isZero();
		assertThat(policyRepository.count()).isZero();
	}

	@Test
	void observationNeverEmitsPolicyRegistered() {
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme", "https://example.com/privacy"));
		StubPolicyFetcher stubFetcher = new StubPolicyFetcher(HTML);
		PolicyObservationService orchestrator = observationService(stubFetcher);

		PolicyObservationResult first = orchestrator.observe(policy.getId());
		assertThat(first.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		stubFetcher.setHtml(REFORMATTED);
		PolicyObservationResult second = orchestrator.observe(policy.getId());
		assertThat(second.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);

		assertThat(countByType("POLICY_REGISTERED")).isZero();
		assertThat(auditEventRepository.count()).isZero();
	}

	@Test
	void nullOwnerAssignmentEmitsOneEvent() {
		User user = userRepository.saveAndFlush(new User());
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme", "https://example.com/privacy"));

		boolean assigned = policyService.assignOwner(user.getId(), policy.getId());

		assertThat(assigned).isTrue();
		List<Map<String, Object>> rows = rowsByType("POLICY_OWNER_ASSIGNED");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("actor_user_id")).isEqualTo(user.getId());
		assertThat(rows.get(0).get("resource_type")).isEqualTo("POLICY");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(policy.getId());
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");
	}

	@Test
	void sameOwnerAssignmentEmitsNothing() {
		User user = userRepository.saveAndFlush(new User());
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme", "https://example.com/privacy"));
		assertThat(policyService.assignOwner(user.getId(), policy.getId())).isTrue();

		assertThat(policyService.assignOwner(user.getId(), policy.getId())).isFalse();

		assertThat(countByType("POLICY_OWNER_ASSIGNED")).isEqualTo(1);
	}

	@Test
	void conflictingAndUnknownAssignmentsEmitNothing() {
		User owner = userRepository.saveAndFlush(new User());
		User other = userRepository.saveAndFlush(new User());
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme", "https://example.com/privacy"));
		policyService.assignOwner(owner.getId(), policy.getId());

		assertThatThrownBy(
				() -> policyService.assignOwner(other.getId(), policy.getId()))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(
				() -> policyService.assignOwner(UUID.randomUUID(), policy.getId()))
				.isInstanceOf(NoSuchElementException.class);
		assertThatThrownBy(
				() -> policyService.assignOwner(owner.getId(), UUID.randomUUID()))
				.isInstanceOf(NoSuchElementException.class);

		assertThat(countByType("POLICY_OWNER_ASSIGNED")).isEqualTo(1);
	}

	@Test
	void auditFailureDoesNotRollBackOwnerAssignment() {
		User user = userRepository.saveAndFlush(new User());
		Policy policy = policyRepository
				.saveAndFlush(new Policy("Acme", "https://example.com/privacy"));
		AuditService failingAudit = mock(AuditService.class);
		doThrow(new AuditAppendException("forced audit failure", null))
				.when(failingAudit).append(any(), any(), any(), any(), any(), any());
		PolicyService failingService = new PolicyService(policyRepository, userRepository,
				transactionManager, failingAudit);

		assertThatThrownBy(() -> failingService.assignOwner(user.getId(), policy.getId()))
				.isInstanceOf(AuditAppendException.class);

		assertThat(policyRepository.findById(policy.getId()).orElseThrow().getOwner()
				.getId()).isEqualTo(user.getId());
		assertThat(auditEventRepository.count()).isZero();
	}

	private Authentication authentication(UUID userId) {
		return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(userId), null,
				List.of());
	}

	private PolicyObservationService observationService(PolicyFetcher fetcher) {
		PolicyObservationPersistenceService persistence = new PolicyObservationPersistenceService(
				versionService, versionRepository, diffEngine, changeRepository, simHash,
				transactionManager);
		return new PolicyObservationService(policyRepository, fetcher,
				new JsoupPolicyContentExtractor(), new DefaultPolicyTextNormalizer(),
				new Sha256PolicyContentHasher(), persistence, attemptService,
				new RetryPolicy(5, Duration.ofMinutes(5), 2.0, Duration.ofHours(6),
						Duration.ofHours(24), new Random()),
				transactionManager);
	}

	private int countByType(String eventType) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ?",
				Integer.class, eventType);
		return count == null ? 0 : count;
	}

	private List<Map<String, Object>> rowsByType(String eventType) {
		return jdbcTemplate.queryForList("SELECT actor_user_id, resource_type, resource_id, "
				+ "metadata FROM audit_event WHERE event_type = ? ORDER BY occurred_at, id",
				eventType);
	}

	static class StubPolicyFetcher implements PolicyFetcher {
		private String html;

		StubPolicyFetcher(String html) {
			this.html = html;
		}

		void setHtml(String html) {
			this.html = html;
		}

		@Override
		public FetchResult fetch(String url) {
			return new FetchResult(url, 200, "text/html", html);
		}
	}
}
