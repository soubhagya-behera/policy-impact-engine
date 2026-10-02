package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptStatus;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptTrigger;
import com.soubhagya.policyimpactengine.policy.application.PolicyOverviewReadService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 17-A — performance proof for the overview read (see DECISIONS.md
 * ADR-036 §9): the cost must be a small constant and must not grow with
 * history. Hibernate statistics are captured around the read service
 * call for a freshly registered policy and again for the same policy
 * after 40 versions, 40 attempts, and 20 assessments have accumulated.
 * Both must cost the identical number of queries, each entity must load
 * exactly once (no N+1 over the history), and no collection may be
 * fetched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyOverviewQueryCountIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-10-02T11:00:00Z");
	private static final int HISTORY = 40;

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
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PolicyOverviewReadService overviewService;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

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
	void overviewCostIsConstantRegardlessOfHistorySize() throws Exception {
		String email = "overview-cost@example.com";
		String token = registerAndLogin(email);
		UUID userId = userIdOf(email);
		Policy policy = policyRepository.findById(
				registerPolicy(token, "Costly", "https://costly.example/privacy")).orElseThrow();

		Statistics stats = statistics();
		stats.clear();
		overviewService.get(userId, policy.getId());
		long freshQueries = stats.getQueryExecutionCount();

		seedHistory(policy, userId);
		assertThat(versionRepository.count()).isEqualTo(HISTORY);
		assertThat(attemptRepository.count()).isEqualTo(HISTORY);

		stats.clear();
		var overview = overviewService.get(userId, policy.getId());
		long heavyQueries = stats.getQueryExecutionCount();

		assertThat(freshQueries).isEqualTo(4L);
		assertThat(heavyQueries).isEqualTo(freshQueries);
		assertThat(overview.latestVersion().versionNumber()).isEqualTo(HISTORY);
		assertThat(overview.latestCheck()).isNotNull();
		assertThat(overview.latestImpact()).isNotNull();
		assertThat(stats.getEntityStatistics(Policy.class.getName()).getLoadCount()).isEqualTo(1);
		assertThat(stats.getEntityStatistics(PolicyVersion.class.getName()).getLoadCount())
				.isEqualTo(1);
		assertThat(stats.getEntityStatistics(ImpactAssessment.class.getName()).getLoadCount())
				.isEqualTo(1);
		assertThat(stats.getEntityStatistics(PolicyFetchAttempt.class.getName()).getLoadCount())
				.isEqualTo(1);
	}

	@Test
	void overviewOfFreshPolicyCostsTheSameConstant() throws Exception {
		String email = "overview-cost-fresh@example.com";
		String token = registerAndLogin(email);
		UUID userId = userIdOf(email);
		Policy policy = policyRepository.findById(
				registerPolicy(token, "Bare", "https://bare.example/privacy")).orElseThrow();

		Statistics stats = statistics();
		stats.clear();
		var overview = overviewService.get(userId, policy.getId());

		assertThat(stats.getQueryExecutionCount()).isEqualTo(4L);
		assertThat(stats.getEntityStatistics(Policy.class.getName()).getLoadCount()).isEqualTo(1);
		assertThat(overview.latestVersion()).isNull();
		assertThat(overview.latestCheck()).isNull();
		assertThat(overview.latestImpact()).isNull();
	}

	private Statistics statistics() {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}

	private void seedHistory(Policy policy, UUID userId) {
		for (int i = 1; i <= HISTORY; i++) {
			versionRepository.saveAndFlush(new PolicyVersion(policy, i,
					"normalized content " + i, String.format("%064x", i)));
			PolicyFetchAttempt attempt = new PolicyFetchAttempt(policy,
					i % 2 == 0 ? PolicyFetchAttemptTrigger.SCHEDULED : PolicyFetchAttemptTrigger.MANUAL,
					1, T0.plusSeconds(i));
			attempt.complete(PolicyFetchAttemptStatus.SUCCESS, 200, 1024L, null, null,
					T0.plusSeconds(i).plusSeconds(3));
			attemptRepository.saveAndFlush(attempt);
		}
		User owner = userRepository.findById(userId).orElseThrow();
		for (int i = 2; i <= HISTORY; i++) {
			PolicyVersion previous = versionRepository
					.findByPolicy_IdAndVersionNumber(policy.getId(), i - 1).orElseThrow();
			PolicyVersion current = versionRepository
					.findByPolicy_IdAndVersionNumber(policy.getId(), i).orElseThrow();
			assessmentRepository.saveAndFlush(new ImpactAssessment(owner, current, previous,
					i == HISTORY ? 70 : 20, i == HISTORY ? ImpactBand.HIGH : ImpactBand.LOW, 1));
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

	private UUID userIdOf(String email) {
		return userRepository.findByEmail(email).map(User::getId).orElseThrow();
	}
}