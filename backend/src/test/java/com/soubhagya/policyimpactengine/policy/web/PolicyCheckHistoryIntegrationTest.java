package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchFailureKind;
import com.soubhagya.policyimpactengine.policy.application.PolicyCheckHistoryReadService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 16-C — end-to-end proof for {@code GET
 * /api/v1/policies/{policyId}/checks} with security filters enabled (see
 * DECISIONS.md ADR-035). Attempts are seeded directly against
 * Testcontainers PostgreSQL; pagination, ordering, isolation, archived
 * readability, read-only behavior, and single-query cost are proven
 * here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyCheckHistoryIntegrationTest {

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
	private PolicyFetchAttemptRepository attemptRepository;

	@Autowired
	private PolicyCheckHistoryReadService historyService;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private AuditVerificationService verificationService;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		attemptRepository.deleteAll();
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void historyMapsEveryStateVerbatimNewestFirst() throws Exception {
		String token = registerAndLogin("checks-mapped@example.com");
		UUID ownerId = userIdOf("checks-mapped@example.com");
		UUID id = registerPolicy(token, "Mapped", "https://mapped.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();
		seedHistory(policy);

		MvcResult result = mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andReturn();

		var rows = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(rows.size()).isEqualTo(6);
		assertThat(rowValues(rows, "status")).containsExactly(
				"SUCCESS", "IN_PROGRESS", "SKIPPED_UNCHANGED", "FAILED", "FAILED", "SUCCESS");
		assertThat(rowValues(rows, "trigger")).containsExactly(
				"SCHEDULED", "MANUAL", "SCHEDULED", "MANUAL", "SCHEDULED", "MANUAL");
		assertThat(rows.get(3).get("failureKind").asText()).isEqualTo("TRANSIENT");
		assertThat(rows.get(4).get("failureKind").asText()).isEqualTo("PERMANENT");
		assertThat(rows.get(4).get("errorMessage").asText()).contains("HTTP 404");
		assertThat(rows.get(4).get("httpStatus").asInt()).isEqualTo(404);
		assertThat(rows.get(1).get("completedAt").isNull()).isTrue();
		assertThat(rows.get(1).get("durationMs").isNull()).isTrue();
		assertThat(rows.get(1).get("failureKind").isNull()).isTrue();
		assertThat(rows.get(0).get("attemptNumber").asInt()).isEqualTo(3);
		assertThat(rows.get(0).has("policyId")).isFalse();
		assertThat(rows.get(0).has("totalCount")).isFalse();
		assertThat(rows.get(0).get("id").asText()).isNotBlank();
		assertThat(policy.getOwner().getId().toString()).isEqualTo(ownerId.toString());
	}

	@Test
	void pendingRowMapsWithLiveNullability() throws Exception {
		String token = registerAndLogin("checks-pending@example.com");
		UUID id = registerPolicy(token, "Pending", "https://pending.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();
		attemptRepository.saveAndFlush(PolicyFetchAttempt.pending(policy,
				PolicyFetchAttemptTrigger.SCHEDULED, 1, T0));

		MvcResult result = mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();

		var rows = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(rows.size()).isEqualTo(1);
		assertThat(rows.get(0).get("status").asText()).isEqualTo("PENDING");
		assertThat(rows.get(0).get("trigger").asText()).isEqualTo("SCHEDULED");
		assertThat(rows.get(0).get("completedAt").isNull()).isTrue();
		assertThat(rows.get(0).get("durationMs").isNull()).isTrue();
	}

	@Test
	void equalTimestampsOrderByIdTieBreakDeterministically() throws Exception {
		String token = registerAndLogin("checks-tie@example.com");
		UUID id = registerPolicy(token, "Tied", "https://tied.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();
		PolicyFetchAttempt first = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, T0);
		first.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1024L, null, null,
				T0.plusSeconds(2));
		attemptRepository.saveAndFlush(first);
		PolicyFetchAttempt second = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, T0);
		second.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1024L, null, null,
				T0.plusSeconds(9));
		attemptRepository.saveAndFlush(second);

		MvcResult result = mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();

		var rows = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(rows.size()).isEqualTo(2);
		String newerFirst = first.getId().toString().compareTo(second.getId().toString()) > 0
				? first.getId().toString()
				: second.getId().toString();
		assertThat(rows.get(0).get("id").asText()).isEqualTo(newerFirst);

		MvcResult paged = mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.queryParam("page", "1")
						.queryParam("size", "1")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		var page = objectMapper.readTree(paged.getResponse().getContentAsString());
		assertThat(page.size()).isEqualTo(1);
		assertThat(page.get(0).get("id").asText()).isNotEqualTo(newerFirst);
	}

	@Test
	void foreignUnknownEmptyAndPaginationBoundaries() throws Exception {
		String token = registerAndLogin("checks-owner@example.com");
		String stranger = registerAndLogin("checks-stranger@example.com");
		UUID id = registerPolicy(token, "Mine", "https://mine.example/privacy");
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.header("Authorization", "Bearer " + stranger))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", unknown)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.queryParam("page", "5")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.queryParam("page", "-1")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.queryParam("size", "101")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.queryParam("size", "huge")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id))
				.andExpect(status().isUnauthorized());

		assertThat(verificationService.verify().isValid()).isTrue();
	}

	@Test
	void archivedHistoryStaysReadableAndReadsChangeNothing() throws Exception {
		String token = registerAndLogin("checks-archived@example.com");
		UUID ownerId = userIdOf("checks-archived@example.com");
		UUID id = registerPolicy(token, "Paused", "https://paused.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();
		seedHistory(policy);
		Instant scheduled = policy.getNextCheckAt();
		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());
		long auditsBefore = auditEventRepository.count();

		MvcResult result = mockMvc.perform(get("/api/v1/policies/{policyId}/checks", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).size())
				.isEqualTo(6);
		assertThat(policyRepository.findById(id).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(policyRepository.findById(id).orElseThrow().getNextCheckAt())
				.isEqualTo(scheduled);
		assertThat(attemptRepository.count()).isEqualTo(6);
		assertThat(auditEventRepository.count()).isEqualTo(auditsBefore);
		assertThat(verificationService.verify().isValid()).isTrue();
		assertThat(ownerId).isNotNull();
	}

	@Test
	void historyPageCostsOneQueryWithNoPolicyLoads() throws Exception {
		String token = registerAndLogin("checks-cost@example.com");
		UUID ownerId = userIdOf("checks-cost@example.com");
		UUID id = registerPolicy(token, "Costly", "https://costly.example/privacy");
		seedHistory(policyRepository.findById(id).orElseThrow());
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);

		stats.clear();
		var page = historyService.list(ownerId, id, 0, 5);
		long queries = stats.getQueryExecutionCount();
		long policyLoads = stats.getEntityStatistics(
				com.soubhagya.policyimpactengine.policy.domain.Policy.class.getName())
				.getFetchCount();

		assertThat(token).isNotBlank();
		assertThat(page).hasSize(5);
		assertThat(queries).isEqualTo(1);
		assertThat(policyLoads).isZero();
	}

	private void seedHistory(Policy policy) {
		Instant base = T0;
		PolicyFetchAttempt success = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, base);
		success.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 4096L, null, null,
				base.plusSeconds(5));
		attemptRepository.saveAndFlush(success);

		PolicyFetchAttempt failedPermanent = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.SCHEDULED, 1, base.plusSeconds(20));
		failedPermanent.complete(PolicyFetchAttemptStatus.FAILED, 404, 512L,
				"HTTP 404 from https://mapped.example/privacy", PolicyFetchFailureKind.PERMANENT,
				base.plusSeconds(22));
		attemptRepository.saveAndFlush(failedPermanent);

		PolicyFetchAttempt failedTransient = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 2, base.plusSeconds(30));
		failedTransient.complete(PolicyFetchAttemptStatus.FAILED, null, null,
				"Stale IN_PROGRESS attempt reaped after lease expiry",
				PolicyFetchFailureKind.TRANSIENT, base.plusSeconds(31));
		attemptRepository.saveAndFlush(failedTransient);

		PolicyFetchAttempt skipped = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.SCHEDULED, 1, base.plusSeconds(40));
		skipped.complete(PolicyFetchAttemptStatus.SKIPPED_UNCHANGED, 200, 4096L, null, null,
				base.plusSeconds(41));
		attemptRepository.saveAndFlush(skipped);

		attemptRepository.saveAndFlush(new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.MANUAL, 1, base.plusSeconds(50)));

		PolicyFetchAttempt retry = new PolicyFetchAttempt(policy,
				PolicyFetchAttemptTrigger.SCHEDULED, 3, base.plusSeconds(60));
		retry.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 4200L, null, null,
				base.plusSeconds(65));
		attemptRepository.saveAndFlush(retry);
	}

	private List<String> rowValues(JsonNode rows, String field) {
		List<String> values = new ArrayList<>();
		rows.forEach(row -> values.add(row.get(field).asText()));
		return values;
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
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private UUID userIdOf(String email) {
		return userRepository.findByEmail(email)
				.map(com.soubhagya.policyimpactengine.user.domain.User::getId).orElseThrow();
	}
}
