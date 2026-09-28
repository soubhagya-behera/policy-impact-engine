package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
 * Phase 14-B/2 — end-to-end change-listing reads with security filters
 * enabled.
 *
 * <p>Proves owner-scoped change listings in transition order with
 * ADR-026 pagination, the V1-only empty page, and 404 isolation for
 * foreign rows. Every test pins its own source address so the shared
 * rate-limit budgets cannot couple tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyChangeListApiIntegrationTest {

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
	void listingReturnsChangesInVersionAndDocumentOrder() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.5.1");
		String token = registerAndLogin("changes-a@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "A Policy", "https://a.example/privacy");
		List<PolicyVersion> versions = observe(policyId, "first", "second", "third");
		UUID v2Id = versions.get(1).getId();
		UUID v3Id = versions.get(2).getId();
		persist(versions.get(0), versions.get(1), PolicyChangeType.MODIFIED, "old one", "new one", 0);
		persist(versions.get(0), versions.get(1), PolicyChangeType.ADDED, null, "brand new", 1);
		persist(versions.get(1), versions.get(2), PolicyChangeType.REMOVED, "gone", null, 0);

		MvcResult result = mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(3))
				.andReturn();

		List<JsonNode> rows = rowsOf(result);
		assertThat(rows.get(0).get("versionNumber").asInt()).isEqualTo(2);
		assertThat(rows.get(0).get("changeOrder").asInt()).isEqualTo(0);
		assertThat(rows.get(0).get("changeType").asText()).isEqualTo("MODIFIED");
		assertThat(rows.get(0).get("oldText").asText()).isEqualTo("old one");
		assertThat(rows.get(0).get("newText").asText()).isEqualTo("new one");
		assertThat(rows.get(0).get("newVersionId").asText()).isEqualTo(v2Id.toString());
		assertThat(rows.get(1).get("versionNumber").asInt()).isEqualTo(2);
		assertThat(rows.get(1).get("changeOrder").asInt()).isEqualTo(1);
		assertThat(rows.get(1).get("changeType").asText()).isEqualTo("ADDED");
		assertThat(rows.get(1).get("oldText").isNull()).isTrue();
		assertThat(rows.get(2).get("versionNumber").asInt()).isEqualTo(3);
		assertThat(rows.get(2).get("changeOrder").asInt()).isEqualTo(0);
		assertThat(rows.get(2).get("changeType").asText()).isEqualTo("REMOVED");
		assertThat(rows.get(2).get("newText").isNull()).isTrue();
		assertThat(rows.get(2).get("newVersionId").asText()).isEqualTo(v3Id.toString());
		for (JsonNode row : rows) {
			assertThat(row.get("id").asText()).isNotBlank();
			assertThat(row.has("policyId")).isFalse();
			assertThat(row.has("normalizedContent")).isFalse();
			assertThat(row.has("contentHash")).isFalse();
		}
	}

	@Test
	void listingPaginatesBeyondDefaultWindow() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.5.2");
		String token = registerAndLogin("changes-page@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "Paged Policy", "https://paged.example/privacy");
		List<PolicyVersion> versions = observe(policyId,
				"c1", "c2", "c3", "c4", "c5", "c6");
		for (int v = 1; v < versions.size(); v++) {
			for (int order = 0; order < 5; order++) {
				persist(versions.get(v - 1), versions.get(v), PolicyChangeType.MODIFIED,
						"old v" + (v + 1) + " #" + order, "new v" + (v + 1) + " #" + order,
						order);
			}
		}

		MvcResult first = mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(20))
				.andReturn();
		List<String> firstKeys = keysOf(first);
		assertThat(firstKeys).hasSize(20);
		assertThat(firstKeys.get(0)).isEqualTo("2:0");
		assertThat(firstKeys.get(19)).isEqualTo("5:4");

		MvcResult second = mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ip)
						.queryParam("page", "2")
						.queryParam("size", "10")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5))
				.andReturn();
		assertThat(keysOf(second)).containsExactly("6:0", "6:1", "6:2", "6:3", "6:4");

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ip)
						.queryParam("page", "5")
						.queryParam("size", "10")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void v1OnlyPolicyListsEmptyAndEmptyDiffReturnsNoChanges() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.5.3");
		String token = registerAndLogin("changes-v1@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "V1 Policy", "https://v1.example/privacy");
		observe(policyId, "only content");

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		// A NEW_VERSION transition with zero persisted rows is a valid
		// empty diff, not a missing transition.
		observe(policyId, "second content");
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
						policyId, 1, 2)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policyId").value(policyId.toString()))
				.andExpect(jsonPath("$.fromVersion").value(1))
				.andExpect(jsonPath("$.toVersion").value(2))
				.andExpect(jsonPath("$.fromVersionId").exists())
				.andExpect(jsonPath("$.toVersionId").exists())
				.andExpect(jsonPath("$.changes").isArray())
				.andExpect(jsonPath("$.changes.length()").value(0));
	}

	@Test
	void foreignUserCannotAccessChangesOrDiff() throws Exception {
		RequestPostProcessor ipA = remoteIp("10.0.5.7");
		RequestPostProcessor ipB = remoteIp("10.0.5.8");
		String tokenA = registerAndLogin("changes-owner@example.com", ipA);
		String tokenB = registerAndLogin("changes-stranger@example.com", ipB);
		UUID policyId = registerPolicy(tokenA, ipA, "Owned Policy", "https://owned.example/privacy");
		List<PolicyVersion> versions = observe(policyId, "owned one", "owned two");
		persist(versions.get(0), versions.get(1), PolicyChangeType.MODIFIED, "a", "b", 0);

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", policyId)
						.with(ipB)
						.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								policyId, 1, 2)
								.with(ipB)
								.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", UUID.randomUUID())
						.with(ipA)
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{from}/diff/{to}",
								UUID.randomUUID(), 1, 2)
								.with(ipA)
								.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
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

	private List<JsonNode> rowsOf(MvcResult result) throws Exception {
		JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(root.isArray()).isTrue();
		return objectMapper.convertValue(root,
				objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
	}

	private List<String> keysOf(MvcResult result) throws Exception {
		return rowsOf(result).stream()
				.map(node -> node.get("versionNumber").asInt() + ":"
						+ node.get("changeOrder").asInt())
				.toList();
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
