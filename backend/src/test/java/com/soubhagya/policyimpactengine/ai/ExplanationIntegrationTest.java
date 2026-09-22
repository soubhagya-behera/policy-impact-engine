package com.soubhagya.policyimpactengine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicySimHash;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchAttemptService;
import com.soubhagya.policyimpactengine.monitoring.application.RetryPolicy;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationPersistenceService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationService;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.DefaultPolicyTextNormalizer;
import com.soubhagya.policyimpactengine.policy.fetch.FetchResult;
import com.soubhagya.policyimpactengine.policy.fetch.JsoupPolicyContentExtractor;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetcher;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 12 — filters-enabled integration tests for the explanation
 * endpoint with a stubbed provider and real JWT authentication.
 * Proves the happy path with authoritative passthrough, ownership
 * isolation, read-only behavior, and the privacy boundary end to
 * end. No real Ollama, no network.
 */
@SpringBootTest(properties = { "ai.enabled=true", "ai.provider=ollama",
		"ai.base-url=http://localhost:9", "ai.model=test-model",
		"ai.connect-timeout=PT2S", "ai.request-timeout=PT30S", "ai.max-input-chars=4000",
		"ai.max-output-tokens=300" })
@AutoConfigureMockMvc
@Testcontainers
class ExplanationIntegrationTest {

	private static final String FIRST_HTML = "<html><body><h1>Privacy Policy</h1>"
			+ "<p>We collect your location data.</p>"
			+ "<p>We retain data for 30 days.</p></body></html>";
	private static final String CHANGED_HTML = "<html><body><h1>Privacy Policy</h1>"
			+ "<p>We collect your precise location data for advertising partners.</p>"
			+ "<p>We retain data for 90 days.</p></body></html>";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private MockMvc mockMvc;
	@Autowired private UserRepository userRepository;
	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyService policyService;
	@Autowired private PolicyVersionService versionService;
	@Autowired private PolicyVersionRepository versionRepository;
	@Autowired private PolicyChangeRecordRepository changeRepository;
	@Autowired private PolicyFetchAttemptService attemptService;
	@Autowired private PolicyFetchAttemptRepository attemptRepository;
	@Autowired private PolicyDiffEngine diffEngine;
	@Autowired private PolicySimHash simHash;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private ImpactAssessmentService assessmentService;
	@Autowired private RecommendationService recommendationService;
	@Autowired private PrivacyConceptRepository conceptRepository;
	@Autowired private ChangeConceptMatchRepository matchRepository;
	@Autowired private ChangeImpactRepository impactRepository;
	@Autowired private ImpactAssessmentRepository assessmentRepository;
	@Autowired private ImpactAssessmentBreakdownRepository breakdownRepository;
	@Autowired private RecommendationRepository recommendationRowRepository;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private UserPrivacyPreferenceRepository preferenceRepository;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private AiExplanationService explanationService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private ObjectMapper objectMapper;

	@MockitoBean
	private AiExplanationProvider provider;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		notificationRepository.deleteAll();
		recommendationRowRepository.deleteAll();
		breakdownRepository.deleteAll();
		assessmentRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		attemptRepository.deleteAll();
		policyRepository.deleteAll();
		preferenceRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void happyPathEchoesAuthoritativeFacts() throws Exception {
	 Fixture fixture = prepare("happy@example.com");
		when(provider.explain(any()))
				.thenReturn(new ExplanationOutcome.Success("Canned prose.", "stub-model"));

		String body = explain(fixture.token(), fixture.assessmentId());
		JsonNode response = objectMapper.readTree(body);

		assertThat(response.get("assessmentId").asText())
				.isEqualTo(fixture.assessmentId().toString());
		assertThat(response.get("explanation").asText()).isEqualTo("Canned prose.");
		assertThat(response.get("aggregateScore").asInt())
				.isEqualTo(fixture.aggregateScore());
		assertThat(response.get("aggregateBand").asText()).isEqualTo(fixture.band());
		List<String> expectedActions = recommendationService
				.listRecommendationResponses(fixture.userId()).stream()
				.filter(row -> row.assessmentId().equals(fixture.assessmentId()))
				.map(row -> row.actionKind())
				.toList();
		List<String> actualActions = new ArrayList<>();
		response.get("actionKinds").forEach(node -> actualActions.add(node.asText()));
		assertThat(actualActions).isEqualTo(expectedActions);
		assertThat(response.get("model").asText()).isEqualTo("stub-model");
		assertThat(response.get("fallback").asBoolean()).isFalse();
		assertThat(response.get("fallbackReason").isNull()).isTrue();
	}

	@Test
	void providerSeesOnlyAllowlistedFacts() throws Exception {
		Fixture fixture = prepare("allowlist@example.com");
		when(provider.explain(any()))
				.thenReturn(new ExplanationOutcome.Success("Canned prose.", "stub-model"));
		explain(fixture.token(), fixture.assessmentId());

		ArgumentCaptor<ExplanationRequest> captor =
				ArgumentCaptor.forClass(ExplanationRequest.class);
		org.mockito.Mockito.verify(provider).explain(captor.capture());
		String prompt = captor.getValue().promptText();
		String lower = prompt.toLowerCase();
		assertThat(prompt).contains("Allowlist Policy");
		assertThat(lower).doesNotContain("http");
		assertThat(lower).doesNotContain("password");
		assertThat(lower).doesNotContain("token");
		assertThat(lower).doesNotContain("secret");
		assertThat(lower).doesNotContain("email");
		assertThat(lower).doesNotContain("authorization");
		assertThat(lower).doesNotContain("evidence");
		assertThat(lower).doesNotContain("fragment");
		assertThat(lower).doesNotContain("advertising partners");
	}

	@Test
	void crossUserAssessmentReturns404() throws Exception {
		Fixture fixture = prepare("owner@example.com");
		String otherToken = registerAndLogin("other@example.com");

		mockMvc.perform(post("/api/v1/me/impact-assessments/" + fixture.assessmentId()
						+ "/explanation")
						.header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void unauthenticatedReturns401() throws Exception {
		mockMvc.perform(post("/api/v1/me/impact-assessments/" + UUID.randomUUID()
						+ "/explanation"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void explanationCreatesNoAuditEventAndChangesNothing() throws Exception {
		Fixture fixture = prepare("readonly@example.com");
		when(provider.explain(any()))
				.thenReturn(new ExplanationOutcome.Success("Canned prose.", "stub-model"));
		String before = assessmentDetail(fixture.token(), fixture.assessmentId());
		int auditBefore = countAll("audit_event");

		explain(fixture.token(), fixture.assessmentId());

		assertThat(countAll("audit_event")).isEqualTo(auditBefore);
		assertThat(assessmentDetail(fixture.token(), fixture.assessmentId())).isEqualTo(before);
	}

	@Test
	void disabledAiFallsBackWithoutProvider() {
		// Manual wiring with AI switched off: the stub provider must
		// never be invoked, whatever it would return.
		AiProperties off = new AiProperties(false, "ollama", "http://localhost:9", "x",
				java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(30), 4000, 300);
		AiExplanationService disabled = new AiExplanationService(assessmentService,
				recommendationService, policyService, conceptRepository, provider, off);
		User user = userRepository.saveAndFlush(new User());
		UUID assessmentId = assess(user.getId());

		var response = disabled.explain(user.getId(), assessmentId);

		assertThat(response.fallback()).isTrue();
		assertThat(response.fallbackReason()).isEqualTo(FallbackReason.DISABLED);
		verifyNoInteractions(provider);
	}

	@Test
	void responseLeaksNoSensitiveData() throws Exception {
		Fixture fixture = prepare("leak@example.com");
		when(provider.explain(any()))
				.thenReturn(new ExplanationOutcome.Success("Canned prose.", "stub-model"));

		String body = explain(fixture.token(), fixture.assessmentId());
		String lower = body.toLowerCase();

		assertThat(lower).doesNotContain("password");
		assertThat(lower).doesNotContain("accesstoken");
		assertThat(lower).doesNotContain("authorization");
		assertThat(lower).doesNotContain("secret");
		assertThat(lower).doesNotContain("leak@example.com");
		assertThat(body).doesNotContain("advertising partners");
		assertThat(body).doesNotContain("actorUser");
	}

	private record Fixture(String token, UUID userId, UUID assessmentId, int aggregateScore,
			String band) {
	}

	private Fixture prepare(String email) throws Exception {
		String token = registerAndLogin(email);
		User user = userRepository.findByEmail(email).orElseThrow();
		Policy policy = policyRepository.saveAndFlush(
				new Policy("Allowlist Policy", "https://example.com/privacy"));
		policyService.assignOwner(user.getId(), policy.getId());
		StubPolicyFetcher stubFetcher = new StubPolicyFetcher(FIRST_HTML);
		PolicyObservationService orchestrator = observationService(stubFetcher);
		orchestrator.observe(policy.getId());
		stubFetcher.setHtml(CHANGED_HTML);
		orchestrator.observe(policy.getId());
		UUID versionId = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow().getId();
		UUID assessmentId = assessmentService.getOrCreateAssessment(user.getId(), versionId)
				.getId();
		recommendationService.getOrCreateRecommendations(user.getId(), versionId);
		var detail = assessmentService.getAssessmentDetail(user.getId(), assessmentId);
		return new Fixture(token, user.getId(), assessmentId, detail.aggregateScore(),
				detail.aggregateBand());
	}

	private UUID assess(UUID userId) {
		Policy policy = policyRepository.saveAndFlush(
				new Policy("Direct Policy", "https://example.com/direct"));
		policyService.assignOwner(userId, policy.getId());
		StubPolicyFetcher stubFetcher = new StubPolicyFetcher(FIRST_HTML);
		PolicyObservationService orchestrator = observationService(stubFetcher);
		orchestrator.observe(policy.getId());
		stubFetcher.setHtml(CHANGED_HTML);
		orchestrator.observe(policy.getId());
		UUID versionId = versionRepository
				.findByPolicy_IdAndVersionNumber(policy.getId(), 2).orElseThrow().getId();
		return assessmentService.getOrCreateAssessment(userId, versionId).getId();
	}

	private String explain(String token, UUID assessmentId) throws Exception {
		return mockMvc.perform(post("/api/v1/me/impact-assessments/" + assessmentId
						+ "/explanation")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andReturn().getResponse().getContentAsString();
	}

	private String assessmentDetail(String token, UUID assessmentId) throws Exception {
		return mockMvc.perform(get("/api/v1/me/impact-assessments/" + assessmentId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
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

	private int countAll(String table) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table,
				Integer.class);
		return count == null ? 0 : count;
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
