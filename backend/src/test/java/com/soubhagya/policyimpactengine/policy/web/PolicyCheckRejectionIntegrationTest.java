package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 16-B/2 — rejection and collision proof for {@code POST
 * /api/v1/policies/{policyId}/check} with security filters enabled (see
 * DECISIONS.md ADR-034). HTTP fetching is stubbed; claiming is real
 * against Testcontainers PostgreSQL.
 *
 * <p>Proves archived/unknown/foreign/malformed/anonymous rejections
 * create no attempt and perform no fetch, and that MANUAL/MANUAL and
 * MANUAL/SCHEDULED collisions converge through the existing V11 claim
 * to a single fetch with a 409 for the loser.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyCheckRejectionIntegrationTest {

	private static final String V1_HTML = "<html><body><h1>Privacy Policy</h1>"
			+ "<p>We collect only the data needed to run your account.</p></body></html>";

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
	private PolicyFetchAttemptService attemptService;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.UserRepository userRepository;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	@MockitoBean
	private PolicyFetcher fetcher;

	@BeforeEach
	void clean() {
		Mockito.clearInvocations(fetcher);
		auditEventRepository.deleteAll();
		jdbcTemplate.update("DELETE FROM policy_fetch_attempt");
		jdbcTemplate.update("DELETE FROM policy_version");
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
		when(fetcher.fetch(anyString())).thenAnswer(invocation -> new FetchResult(
				(String) invocation.getArgument(0), 200, "text/html", V1_HTML));
	}

	@Test
	void archivedPolicyIs409WithNoAttemptAndNoFetch() throws Exception {
		String token = registerAndLogin("check-archived@example.com");
		UUID id = registerPolicy(token, "Paused", "https://paused.example/privacy");
		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Conflict"))
				.andExpect(jsonPath("$.detail").value(
						"Policy " + id + " is archived; reactivate it before checking"));

		assertThat(attemptCount(id)).isZero();
		verify(fetcher, never()).fetch(anyString());
		assertThat(policyRepository.findById(id).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
	}

	@Test
	void unknownForeignMalformedAndAnonymousChecksRejected() throws Exception {
		String token = registerAndLogin("check-owner@example.com");
		String stranger = registerAndLogin("check-stranger@example.com");
		UUID id = registerPolicy(token, "Mine", "https://mine.example/privacy");
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", unknown)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + stranger))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer invalid.token.here"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		assertThat(attemptCount(id)).isZero();
	}

	@Test
	void scheduledInFlightCheckRejectsManualCheck() throws Exception {
		String token = registerAndLogin("check-raced@example.com");
		UUID id = registerPolicy(token, "Raced", "https://raced.example/privacy");
		Policy policy = policyRepository.findById(id).orElseThrow();
		attemptService.beginAttempt(policy, PolicyFetchAttemptTrigger.SCHEDULED);

		mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Conflict"))
				.andExpect(jsonPath("$.detail").value("Policy " + id
						+ " already has an in-flight check; refusing duplicate work"));

		verify(fetcher, never()).fetch(anyString());
	}

	@Test
	void concurrentManualChecksConvergeWithOneFetch() throws Exception {
		String token = registerAndLogin("check-concurrent@example.com");
		UUID id = registerPolicy(token, "Hot", "https://hot.example/privacy");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger fetches = new AtomicInteger();
		when(fetcher.fetch(anyString())).thenAnswer(invocation -> {
			fetches.incrementAndGet();
			entered.countDown();
			if (!release.await(60, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Fetch gate timed out");
			}
			return new FetchResult((String) invocation.getArgument(0), 200, "text/html", V1_HTML);
		});

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<MvcResult> first = pool.submit(() -> mockMvc.perform(
							post("/api/v1/policies/{policyId}/check", id)
									.header("Authorization", "Bearer " + token))
					.andReturn());
			assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();
			MvcResult second = mockMvc.perform(post("/api/v1/policies/{policyId}/check", id)
							.header("Authorization", "Bearer " + token))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.title").value("Conflict"))
					.andReturn();
			assertThat(second.getResponse().getStatus()).isEqualTo(409);
			release.countDown();

			MvcResult won = first.get(60, TimeUnit.SECONDS);
			assertThat(won.getResponse().getStatus()).isEqualTo(200);
			assertThat(fetches.get()).isEqualTo(1);
			assertThat(versionCount(id)).isEqualTo(1);
		}
		finally {
			release.countDown();
			pool.shutdownNow();
		}
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

	private int attemptCount(UUID policyId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM policy_fetch_attempt WHERE policy_id = ?",
				Integer.class, policyId);
		return count == null ? 0 : count;
	}

	private int versionCount(UUID policyId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM policy_version WHERE policy_id = ?", Integer.class, policyId);
		return count == null ? 0 : count;
	}

	private Integer jdbcTemplateQuery(String sql, UUID id) {
		return jdbcTemplate.queryForObject(sql, Integer.class, id);
	}
}
