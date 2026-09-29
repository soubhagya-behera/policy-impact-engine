package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.notification.application.NotificationFanOutService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.JsoupPolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetchException;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 14-C/4a — HTTP-level delete regression for the {@code
 * ACTIVE → ARCHIVED} lifecycle (see DECISIONS.md ADR-030) with
 * security filters enabled: concurrent owner DELETEs all return
 * {@code 204 No Content} with exactly one {@code POLICY_ARCHIVED}
 * event, and every existing read contract (detail, list, versions,
 * snapshot, changes, adjacent diff, change assessment, impact
 * summary, audit feed) keeps working on the archived policy with
 * real history — without creating or deleting any domain row.
 * Production code is untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyArchiveDeleteRegressionTest {

	private static final String V1_HTML = "<html><body><p>Welcome to our privacy policy.</p>"
			+ "<p>This policy applies to all users of the service.</p></body></html>";
	private static final String V2_HTML = "<html><body><p>Welcome to our privacy policy.</p>"
			+ "<p>This policy applies to all users of the service.</p>"
			+ "<p>We share your location data with third-party advertising partners.</p></body></html>";

	private static final List<String> HISTORY_TABLES = List.of("policy", "policy_version",
			"policy_change", "change_concept_match", "change_impact", "impact_assessment",
			"impact_assessment_breakdown", "recommendation", "notification",
			"policy_fetch_attempt", "audit_event");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private MockMvc mockMvc;
	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyObservationPersistenceService persistenceService;
	@Autowired private PolicyFetchAttemptService attemptService;
	@Autowired private NotificationFanOutService fanOutService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
	@Autowired private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;
	@Autowired private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;
	@Autowired private com.soubhagya.policyimpactengine.user.domain.UserRepository userRepository;
	@Autowired private com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository versionRepository;
	@Autowired private com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository changeRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		for (String table : List.of("notification", "recommendation",
				"impact_assessment_breakdown", "impact_assessment", "policy_fetch_attempt",
				"change_impact", "change_concept_match", "policy_change", "policy_version",
				"policy")) {
			jdbcTemplate.execute("DELETE FROM " + table);
		}
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void concurrentOwnerDeletesAllReturn204WithSingleAuditEvent() throws Exception {
		String token = registerAndLogin("race-deleter@example.com");
		UUID id = registerPolicy(token, "Raced", "https://raced.example/privacy");
		int contenders = 6;
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		CountDownLatch ready = new CountDownLatch(contenders);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < contenders; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					if (!start.await(30, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Start gate timed out");
					}
					return mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
									.header("Authorization", "Bearer " + token))
							.andReturn().getResponse().getStatus();
				}));
			}
			assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			for (Future<Integer> future : futures) {
				assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(204);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(policyRepository.findById(id).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);
	}

	@Test
	void archivedReadsReplayExistingContracts() throws Exception {
		String token = registerAndLogin("replay-reader@example.com");
		UUID id = registerPolicy(token, "Kept", "https://kept.example/privacy");
		seedHistory(id);
		UUID versionId = versionRepository.findByPolicy_IdAndVersionNumber(id, 2)
				.orElseThrow().getId();
		UUID changeId = changeRepository.findAll().get(0).getId();

		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		mockMvc.perform(get("/api/v1/policies/{id}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("ARCHIVED"));

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("ARCHIVED"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].versionNumber").value(1))
				.andExpect(jsonPath("$[1].versionNumber").value(2));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}", id, versionId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(versionId.toString()));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value((int) changeRepository.count()));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						id, 1, 2)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assessment").exists());

		mockMvc.perform(get("/api/v1/me/impact-summary")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAssessments").value(1));

		MvcResult auditFeed = mockMvc.perform(get("/api/v1/me/audit-events")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(auditFeed.getResponse().getContentAsString())
				.contains("POLICY_ARCHIVED");
	}

	@Test
	void readsAfterArchiveChangeNoDomainRows() throws Exception {
		String token = registerAndLogin("readonly-reader@example.com");
		UUID id = registerPolicy(token, "Still", "https://still.example/privacy");
		seedHistory(id);
		UUID versionId = versionRepository.findByPolicy_IdAndVersionNumber(id, 2)
				.orElseThrow().getId();
		UUID changeId = changeRepository.findAll().get(0).getId();
		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		Map<String, Integer> before = rowCounts();

		mockMvc.perform(get("/api/v1/policies/{id}", id)
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/policies")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", id)
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}", id, versionId)
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", id)
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						id, 1, 2).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/changes/{changeId}/assessment", changeId)
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/me/impact-summary")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/me/audit-events")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());

		assertThat(rowCounts()).isEqualTo(before);
	}

	private void seedHistory(UUID policyId) {
		String url = policyRepository.findById(policyId).orElseThrow().getUrl();
		MapFetcher fetcher = new MapFetcher(Map.of(url, V1_HTML));
		PolicyObservationService orchestrator = new PolicyObservationService(policyRepository,
				fetcher, new JsoupPolicyContentExtractor(), new DefaultPolicyTextNormalizer(),
				new Sha256PolicyContentHasher(), persistenceService, attemptService,
				new RetryPolicy(5, Duration.ofMinutes(5), 2.0, Duration.ofHours(6),
						Duration.ofHours(24), new Random()),
				transactionManager);
		orchestrator.observe(policyId, PolicyFetchAttemptTrigger.MANUAL);
		fetcher.put(url, V2_HTML);
		PolicyObservationResult second = orchestrator.observe(policyId,
				PolicyFetchAttemptTrigger.MANUAL);
		fanOutService.fanOut(policyId, second);
	}

	private Map<String, Integer> rowCounts() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String table : HISTORY_TABLES) {
			Integer count = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM " + table, Integer.class);
			counts.put(table, count == null ? 0 : count);
		}
		return counts;
	}

	private int countByType(String eventType) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ?", Integer.class, eventType);
		return count == null ? 0 : count;
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

	private static final class MapFetcher implements PolicyFetcher {
		private final Map<String, String> routes = new ConcurrentHashMap<>();

		MapFetcher(Map<String, String> routes) {
			this.routes.putAll(routes);
		}

		void put(String url, String html) {
			routes.put(url, html);
		}

		@Override
		public FetchResult fetch(String url) {
			String html = routes.get(url);
			if (html == null) {
				throw new PolicyFetchException("no fixture for " + url);
			}
			return new FetchResult(url, 200, "text/html", html);
		}
	}
}
