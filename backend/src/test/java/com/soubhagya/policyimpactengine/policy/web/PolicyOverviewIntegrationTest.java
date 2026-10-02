package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.application.AuditVerificationService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 17-A — end-to-end proof for {@code GET
 * /api/v1/policies/{policyId}/overview} with security filters enabled (see
 * DECISIONS.md ADR-036). Versions, attempts, and assessments are seeded
 * directly against Testcontainers PostgreSQL; the three latest-fact
 * selections, the empty state, archived readability, user isolation, and
 * the read-only guarantee are proven here. Query-cost proof lives in
 * {@code PolicyOverviewQueryCountIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyOverviewIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private PolicyFetchAttemptRepository attemptRepository;

	@Autowired
	private ImpactAssessmentRepository assessmentRepository;

	@Autowired
	private AuditEventRepository auditEventRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AuditVerificationService verificationService;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		assessmentRepository.deleteAll();
		attemptRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void ownedActivePolicyReportsEveryLatestFactVerbatim() throws Exception {
		String token = registerAndLogin("overview-mapped@example.com");
		UUID id = registerPolicy(token, "Mapped", "https://mapped.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();
		seedVersions(policy, 3);

		PolicyFetchAttempt failed = new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.SCHEDULED, 1, T0);
		failed.complete(PolicyFetchAttemptStatus.FAILED, 503, null, "upstream unavailable",
				PolicyFetchFailureKind.TRANSIENT, T0.plusSeconds(4));
		attemptRepository.saveAndFlush(failed);

		PolicyFetchAttempt retried = new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL, 2,
				T0.plusSeconds(30));
		retried.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 4096L, null, null,
				T0.plusSeconds(34));
		attemptRepository.saveAndFlush(retried);
		JsonNode body = overview(token, id);

		assertThat(body.get("policyId").asText()).isEqualTo(id.toString());
		assertThat(body.get("name").asText()).isEqualTo("Mapped");
		assertThat(body.get("url").asText()).isEqualTo("https://mapped.example/privacy");
		assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
		assertThat(body.get("nextCheckAt").asText())
				.isEqualTo(policy.getNextCheckAt().toString());
		assertThat(body.get("latestVersion").get("versionNumber").asInt()).isEqualTo(3);
		assertThat(body.get("latestVersion").get("contentHash").asText()).hasSize(64);
		assertThat(body.get("latestCheck").get("id").asText()).isEqualTo(retried.getId().toString());
		assertThat(body.get("latestCheck").get("trigger").asText()).isEqualTo("MANUAL");
		assertThat(body.get("latestCheck").get("attemptNumber").asInt()).isEqualTo(2);
		assertThat(body.get("latestCheck").get("status").asText()).isEqualTo("SUCCESS");
		assertThat(body.get("latestCheck").get("failureKind").isNull()).isTrue();
		assertThat(body.get("latestCheck").get("httpStatus").asInt()).isEqualTo(200);
		assertThat(body.get("latestCheck").get("bytesFetched").asLong()).isEqualTo(4096L);
		assertThat(body.get("latestCheck").get("durationMs").asLong()).isEqualTo(4000L);
		assertThat(body.get("latestCheck").get("completedAt").asText())
				.isEqualTo(T0.plusSeconds(34).toString());
		assertThat(body.get("latestCheck").has("errorMessage")).isFalse();
		assertThat(body.get("latestImpact").isNull()).isTrue();
	}

	@Test
	void freshPolicyReportsNullLatestFactsAndKeepsScheduleVerbatim() throws Exception {
		String token = registerAndLogin("overview-fresh@example.com");
		UUID id = registerPolicy(token, "Fresh", "https://fresh.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();
		policy.setNextCheckAt(T0.plusSeconds(3600));
		policyRepository.saveAndFlush(policy);

		JsonNode body = overview(token, id);

		assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
		assertThat(body.get("nextCheckAt").asText()).isEqualTo(T0.plusSeconds(3600).toString());
		assertThat(body.get("latestVersion").isNull()).isTrue();
		assertThat(body.get("latestCheck").isNull()).isTrue();
		assertThat(body.get("latestImpact").isNull()).isTrue();
		assertThat(attemptRepository.count()).isZero();
		assertThat(assessmentRepository.count()).isZero();
	}

	@Test
	void latestCheckPrefersNewerStartedAtThenIdDescTieBreak() throws Exception {
		String token = registerAndLogin("overview-tie@example.com");
		UUID id = registerPolicy(token, "Tied", "https://tied.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();

		PolicyFetchAttempt older = new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.SCHEDULED, 1, T0);
		older.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 10L, null, null, T0.plusSeconds(2));
		attemptRepository.saveAndFlush(older);

		PolicyFetchAttempt sameInstant = new PolicyFetchAttempt(policy, PolicyFetchAttemptTrigger.MANUAL,
				1, T0);
		sameInstant.complete(PolicyFetchAttemptStatus.SKIPPED_UNCHANGED, 200, 20L, null, null,
				T0.plusSeconds(9));
		attemptRepository.saveAndFlush(sameInstant);

		String expected = older.getId().toString().compareTo(sameInstant.getId().toString()) > 0
				? older.getId().toString() : sameInstant.getId().toString();
		JsonNode body = overview(token, id);
		assertThat(body.get("latestCheck").get("id").asText()).isEqualTo(expected);
		assertThat(body.get("latestCheck").get("status").asText()).isIn("SUCCESS", "SKIPPED_UNCHANGED");
	}

	@Test
	void livePendingAndInProgressChecksKeepNullDurationAndCompletion() throws Exception {
		String token = registerAndLogin("overview-live@example.com");

		Policy pending = policyRepository.findById(
				registerPolicy(token, "PendingRow", "https://pendingrow.example/privacy")).orElseThrow();
		seedTerminal(pending, T0);
		attemptRepository.saveAndFlush(PolicyFetchAttempt.pending(pending,
				PolicyFetchAttemptTrigger.SCHEDULED, 2, T0.plusSeconds(60)));

		Policy running = policyRepository.findById(
				registerPolicy(token, "RunningRow", "https://runningrow.example/privacy")).orElseThrow();
		seedTerminal(running, T0);
		attemptRepository.saveAndFlush(new PolicyFetchAttempt(running,
				PolicyFetchAttemptTrigger.MANUAL, 2, T0.plusSeconds(60)));

		for (Policy policy : new Policy[] { pending, running }) {
			JsonNode check = overview(token, policy.getId()).get("latestCheck");
			assertThat(check.get("attemptNumber").asInt()).isEqualTo(2);
			assertThat(check.get("status").asText()).isIn("PENDING", "IN_PROGRESS");
			assertThat(check.get("failureKind").isNull()).isTrue();
			assertThat(check.get("durationMs").isNull()).isTrue();
			assertThat(check.get("completedAt").isNull()).isTrue();
		}
	}

	@Test
	void latestImpactIsTheNewestRowForThisOwnerAndPolicyOnly() throws Exception {
		String ownerToken = registerAndLogin("overview-impact@example.com");
		UUID ownerId = userIdOf("overview-impact@example.com");
		String strangerToken = registerAndLogin("overview-stranger@example.com");
		UUID strangerId = userIdOf("overview-stranger@example.com");

		Policy policy = policyRepository.findById(registerPolicy(ownerToken, "Scored",
				"https://scored.example/privacy")).orElseThrow();
		seedVersions(policy, 3);
		PolicyVersion v2 = version(policy, 2);
		PolicyVersion v3 = version(policy, 3);
		Policy otherPolicy = policyRepository.findById(
				registerPolicy(ownerToken, "Other", "https://other.example/privacy")).orElseThrow();
		seedVersions(otherPolicy, 2);
		PolicyVersion otherV2 = version(otherPolicy, 2);

		User owner = userRepository.findById(ownerId).orElseThrow();
		User stranger = userRepository.findById(strangerId).orElseThrow();

		ImpactAssessment older = assessmentRepository.saveAndFlush(
				new ImpactAssessment(owner, v2, version(policy, 1), 20, ImpactBand.LOW, 1));
		ImpactAssessment newest = assessmentRepository.saveAndFlush(
				new ImpactAssessment(owner, v3, v2, 75, ImpactBand.HIGH, 1));
		assessmentRepository.saveAndFlush(new ImpactAssessment(owner, otherV2,
				version(otherPolicy, 1), 95, ImpactBand.CRITICAL, 1));
		assessmentRepository.saveAndFlush(new ImpactAssessment(stranger, v3, v2,
				100, ImpactBand.CRITICAL, 1));

		ImpactAssessment expected = newest(older, newest);
		long assessmentsBefore = assessmentRepository.count();

		JsonNode impact = overview(ownerToken, policy.getId()).get("latestImpact");
		assertThat(impact.get("aggregateScore").asInt()).isEqualTo(expected.getAggregateScore());
		assertThat(impact.get("band").asText()).isEqualTo(expected.getAggregateBand().name());
		assertThat(impact.get("assessedAt").asText()).isEqualTo(expected.getCreatedAt().toString());
		assertThat(assessmentRepository.count()).isEqualTo(assessmentsBefore);
	}

	@Test
	void unknownForeignMalformedAndUnauthenticatedAreRejected() throws Exception {
		String token = registerAndLogin("overview-iso@example.com");
		String stranger = registerAndLogin("overview-iso-stranger@example.com");
		UUID id = registerPolicy(token, "Mine", "https://mine.example/privacy");
		seedVersions(policyRepository.findById(id).orElseThrow(), 2);

		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", id)
						.header("Authorization", "Bearer " + stranger))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", UUID.randomUUID())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));
		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", id))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
		mockMvc.perform(get("/api/v1/policies/{policyId}/overview", id)
						.header("Authorization", "Bearer not.a.token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void archivedPolicyStaysReadableAndReadsMutateNothing() throws Exception {
		String token = registerAndLogin("overview-archived@example.com");
		UUID id = registerPolicy(token, "Paused", "https://paused.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();
		seedVersions(policy, 2);
		seedTerminal(policy, T0);
		assessmentRepository.saveAndFlush(new ImpactAssessment(
				userRepository.findById(userIdOf("overview-archived@example.com")).orElseThrow(),
				version(policy, 2), version(policy, 1), 42, ImpactBand.MEDIUM, 1));

		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());
		Instant frozenSchedule = policyRepository.findById(id).orElseThrow().getNextCheckAt();
		long auditsBefore = auditEventRepository.count();
		long policiesBefore = policyRepository.count();
		long versionsBefore = versionRepository.count();
		long attemptsBefore = attemptRepository.count();
		long assessmentsBefore = assessmentRepository.count();

		JsonNode body = overview(token, id);

		assertThat(body.get("status").asText()).isEqualTo("ARCHIVED");
		assertThat(body.get("nextCheckAt").asText()).isEqualTo(frozenSchedule.toString());
		assertThat(body.get("latestVersion").get("versionNumber").asInt()).isEqualTo(2);
		assertThat(body.get("latestCheck").get("status").asText()).isEqualTo("SUCCESS");
		assertThat(body.get("latestImpact").get("aggregateScore").asInt()).isEqualTo(42);
		assertThat(policyRepository.count()).isEqualTo(policiesBefore);
		assertThat(versionRepository.count()).isEqualTo(versionsBefore);
		assertThat(attemptRepository.count()).isEqualTo(attemptsBefore);
		assertThat(assessmentRepository.count()).isEqualTo(assessmentsBefore);
		assertThat(auditEventRepository.count()).isEqualTo(auditsBefore);
		Policy reread = policyRepository.findById(id).orElseThrow();
		assertThat(reread.getStatus()).isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(reread.getNextCheckAt()).isEqualTo(frozenSchedule);
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	/**
	 * Newest row under {@code createdAt DESC, id DESC}, derived the same way
	 * the repository query orders it. {@code @CreationTimestamp} has
	 * millisecond resolution, so two rows seeded back to back can share an
	 * instant and the id tie-break decides; deriving the expectation
	 * identically keeps this independent of clock granularity.
	 */
	private ImpactAssessment newest(ImpactAssessment first, ImpactAssessment second) {
		if (first.getCreatedAt().isAfter(second.getCreatedAt())) {
			return first;
		}
		if (second.getCreatedAt().isAfter(first.getCreatedAt())) {
			return second;
		}
		return first.getId().toString().compareTo(second.getId().toString()) > 0 ? first : second;
	}

	private PolicyVersion version(Policy policy, int number) {
		return versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), number).orElseThrow();
	}

	private void seedVersions(Policy policy, int count) {
		for (int i = 1; i <= count; i++) {
			versionRepository.saveAndFlush(new PolicyVersion(policy, i,
					"normalized content " + i, String.format("%064x", i)));
		}
	}

	private void seedTerminal(Policy policy, Instant startedAt) {
		PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.SCHEDULED, 1, startedAt);
		attempt.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1024L, null, null,
				startedAt.plusSeconds(3));
		attemptRepository.saveAndFlush(attempt);
	}

	private JsonNode overview(String token, UUID policyId) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/policies/{policyId}/overview", policyId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private UUID registerPolicy(String token, String name, String url) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/policies")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(objectMapper
				.readTree(result.getResponse().getContentAsString()).get("id").asText());
	}

	private String registerAndLogin(String email) throws Exception {
		String credentials = "{\"email\":\"%s\",\"password\":\"correct-horse-1\"}"
				.formatted(email);
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(credentials))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(credentials))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private UUID userIdOf(String email) {
		return userRepository.findByEmail(email).map(User::getId).orElseThrow();
	}
}