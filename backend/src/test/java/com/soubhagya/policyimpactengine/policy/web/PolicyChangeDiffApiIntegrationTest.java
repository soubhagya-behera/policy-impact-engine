package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionService;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 14-B/2 — end-to-end adjacent-version diff reads with security
 * filters enabled.
 *
 * <p>Proves diffs return exactly the persisted transition rows with no
 * recomputation or concatenation, adjacent-only validation, 404
 * isolation for foreign and mismatched versions, and that reads never
 * mutate state. Every test pins its own source address so the shared
 * rate-limit budgets cannot couple tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyChangeDiffApiIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private PolicyChangeRecordRepository changeRepository;

	@Autowired
	private PolicyVersionService versionService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ObjectMapper objectMapper;

	// Phase 11C: registration, login, and policy registration emit
	// audit rows referencing users, so audit rows go first or user
	// deletion violates the actor foreign key.
	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	// Phase 14-A/3a: successful logins persist refresh rows referencing
	// users, so refresh rows go before user deletion.
	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void adjacentDiffReturnsExactlyPersistedTransitionRows() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.5.4");
		String token = registerAndLogin("changes-diff@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "Diff Policy", "https://diff.example/privacy");
		List<PolicyVersion> versions = observe(policyId, "one", "two", "three");
		persist(versions.get(0), versions.get(1), PolicyChangeType.MODIFIED, "line a", "line A", 0);
		persist(versions.get(0), versions.get(1), PolicyChangeType.ADDED, null, "extra", 1);
		persist(versions.get(1), versions.get(2), PolicyChangeType.REMOVED, "stale", null, 0);

		MvcResult first = mockMvc.perform(
						get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 1, 2)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policyId").value(policyId.toString()))
				.andExpect(jsonPath("$.fromVersion").value(1))
				.andExpect(jsonPath("$.toVersion").value(2))
				.andExpect(jsonPath("$.fromVersionId").value(versions.get(0).getId().toString()))
				.andExpect(jsonPath("$.toVersionId").value(versions.get(1).getId().toString()))
				.andExpect(jsonPath("$.changes.length()").value(2))
				.andReturn();
		List<JsonNode> firstRows = changesOf(first);
		assertThat(firstRows.get(0).get("oldText").asText()).isEqualTo("line a");
		assertThat(firstRows.get(0).get("newText").asText()).isEqualTo("line A");
		assertThat(firstRows.get(0).get("changeOrder").asInt()).isEqualTo(0);
		assertThat(firstRows.get(1).get("newText").asText()).isEqualTo("extra");
		assertThat(firstRows.get(1).get("changeOrder").asInt()).isEqualTo(1);

		// The second transition carries only its own rows: transitions
		// are never concatenated.
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 2, 3)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fromVersion").value(2))
				.andExpect(jsonPath("$.toVersion").value(3))
				.andExpect(jsonPath("$.changes.length()").value(1))
				.andExpect(jsonPath("$.changes[0].changeType").value("REMOVED"))
				.andExpect(jsonPath("$.changes[0].oldText").value("stale"));
	}

	@Test
	void nonAdjacentDiffReturns400() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.5.5");
		String token = registerAndLogin("changes-range@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "Range Policy", "https://range.example/privacy");
		observe(policyId, "one", "two", "three");

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 1, 3)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 2, 2)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 3, 1)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 0, 1)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));
	}

	@Test
	void wrongPredecessorLinkageReturns404() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.5.6");
		String token = registerAndLogin("changes-link@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "Link Policy", "https://link.example/privacy");
		UUID otherId = registerPolicy(token, ip, "Other Policy", "https://other.example/privacy");
		List<PolicyVersion> versions = observe(policyId, "one", "two");
		List<PolicyVersion> others = observe(otherId, "alpha");
		// Tampered history: the successor's persisted row points at a
		// version that is not the requested predecessor.
		persist(others.get(0), versions.get(1), PolicyChangeType.ADDED, null, "foreign root", 0);

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 1, 2)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void readsDoNotMutateStateRejectBadInputAndStayAnonymous() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.5.9");
		String token = registerAndLogin("changes-readonly@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "Readonly Policy", "https://readonly.example/privacy");
		List<PolicyVersion> versions = observe(policyId, "readonly one", "readonly two");
		persist(versions.get(0), versions.get(1), PolicyChangeType.MODIFIED, "x", "y", 0);
		long policiesBefore = policyRepository.count();
		long versionsBefore = versionRepository.count();
		long changesBefore = changeRepository.count();

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 1, 2)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		assertThat(policyRepository.count()).isEqualTo(policiesBefore);
		assertThat(versionRepository.count()).isEqualTo(versionsBefore);
		assertThat(changeRepository.count()).isEqualTo(changesBefore);

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", "not-a-uuid")
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, "one", 2)
								.with(ip)
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ip))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 1, 2)
								.with(ip))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ip)
						.queryParam("size", "101")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));

		assertThat(policyRepository.count()).isEqualTo(policiesBefore);
		assertThat(versionRepository.count()).isEqualTo(versionsBefore);
		assertThat(changeRepository.count()).isEqualTo(changesBefore);
	}

	@Test
	void readServiceNeverTouchesTheDiffEngine() {
		assertThat(Arrays.stream(
				com.soubhagya.policyimpactengine.policy.application.PolicyChangeReadService.class
						.getDeclaredFields())
				.map(field -> field.getType().getName())
				.noneMatch(type -> type.contains("DiffEngine"))).isTrue();
		assertThat(Arrays.stream(
				com.soubhagya.policyimpactengine.policy.application.PolicyChangeReadService.class
						.getDeclaredConstructors())
				.flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
				.map(Class::getName)
				.noneMatch(type -> type.contains("DiffEngine"))).isTrue();
	}

	private void persist(PolicyVersion previous, PolicyVersion next, PolicyChangeType type,
			String oldText, String newText, int order) {
		changeRepository.saveAndFlush(
				new PolicyChangeRecord(previous, next, type, oldText, newText, order));
	}

	private String registerAndLogin(String email, RequestPostProcessor ip) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
						.with(ip)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private UUID registerPolicy(String token, RequestPostProcessor ip,
			String name, String url) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/policies")
						.with(ip)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"%s","url":"%s"}
								""".formatted(name, url)))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
				.get("id").asText());
	}

	private List<PolicyVersion> observe(UUID policyId, String... contents) {
		java.util.ArrayList<PolicyVersion> versions = new ArrayList<>();
		for (String content : contents) {
			versions.add(versionService.observe(policyId, content, sha256Hex(content)).version());
		}
		return versions;
	}

	private List<JsonNode> changesOf(MvcResult result) throws Exception {
		JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
		JsonNode changes = root.get("changes");
		assertThat(changes.isArray()).isTrue();
		return objectMapper.convertValue(changes,
				objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
	}

	private static String sha256Hex(String content) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(content.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (java.security.NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
	}

	/**
	 * Pins one source address per test. Rate-limit budgets are keyed
	 * by IP and the limiter outlives any single test, so sharing the
	 * default MockMvc address would couple tests through the budget.
	 */
	private static RequestPostProcessor remoteIp(String address) {
		return (MockHttpServletRequest request) -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
