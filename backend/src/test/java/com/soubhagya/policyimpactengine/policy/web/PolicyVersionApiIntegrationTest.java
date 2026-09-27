package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

import com.soubhagya.policyimpactengine.policy.application.PolicyVersionService;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 14-B/1 — end-to-end version-history reads with security
 * filters enabled.
 *
 * <p>Proves owner-scoped listings in version-number ascending order
 * with ADR-026 pagination, single snapshots with content, 404
 * isolation for foreign rows, and that reads never mutate state.
 * Every test pins its own source address so the shared rate-limit
 * budgets cannot couple tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyVersionApiIntegrationTest {

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
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void listingReturnsVersionsInAscendingOrderWithoutContent() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.4.1");
		String token = registerAndLogin("versions-a@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "A Policy", "https://a.example/privacy");
		observe(policyId, "first content", "second content", "third content");

		MvcResult result = mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(3))
				.andReturn();

		List<Integer> numbers = versionsOf(result).stream()
				.map(node -> node.get("versionNumber").asInt()).toList();
		assertThat(numbers).containsExactly(1, 2, 3);
		assertThat(versionsOf(result).get(0).get("policyId").asText())
				.isEqualTo(policyId.toString());
		assertThat(versionsOf(result).get(0).get("contentHash").asText())
				.isEqualTo(sha256Hex("first content"));
		for (JsonNode node : versionsOf(result)) {
			assertThat(node.has("normalizedContent")).isFalse();
			assertThat(node.get("observedAt").asText()).isNotBlank();
		}
	}

	@Test
	void listingPaginatesWithDefaultsAndEmptyBeyondLastPage() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.4.2");
		String token = registerAndLogin("versions-page@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "Paged Policy", "https://paged.example/privacy");
		for (int i = 1; i <= 25; i++) {
			observeOne(policyId, "paged content " + i);
		}

		MvcResult first = mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(20))
				.andReturn();
		assertThat(numbersOf(first)).containsExactly(
				1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);

		MvcResult second = mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ip)
						.queryParam("page", "1")
						.queryParam("size", "10")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(10))
				.andReturn();
		assertThat(numbersOf(second)).containsExactly(
				11, 12, 13, 14, 15, 16, 17, 18, 19, 20);

		MvcResult third = mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ip)
						.queryParam("page", "2")
						.queryParam("size", "10")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5))
				.andReturn();
		assertThat(numbersOf(third)).containsExactly(21, 22, 23, 24, 25);

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ip)
						.queryParam("page", "5")
						.queryParam("size", "10")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void foreignPolicyListsEmptyAndForeignVersionsReturn404() throws Exception {
		RequestPostProcessor ipA = remoteIp("10.0.4.3");
		RequestPostProcessor ipB = remoteIp("10.0.4.4");
		String tokenA = registerAndLogin("versions-owner@example.com", ipA);
		String tokenB = registerAndLogin("versions-stranger@example.com", ipB);
		UUID policyId = registerPolicy(tokenA, ipA, "Owned Policy", "https://owned.example/privacy");
		observe(policyId, "owned content");
		UUID versionId = versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policyId)
				.get(0).getId();

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ipB)
						.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						policyId, versionId)
						.with(ipB)
						.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						UUID.randomUUID(), UUID.randomUUID())
						.with(ipA)
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void detailReturnsSnapshotAndMismatchReturns404() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.4.5");
		String token = registerAndLogin("versions-detail@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "Detail Policy", "https://detail.example/privacy");
		UUID otherId = registerPolicy(token, ip, "Other Policy", "https://other.example/privacy");
		observe(policyId, "detail content");
		UUID versionId = versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policyId)
				.get(0).getId();

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						policyId, versionId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(versionId.toString()))
				.andExpect(jsonPath("$.policyId").value(policyId.toString()))
				.andExpect(jsonPath("$.versionNumber").value(1))
				.andExpect(jsonPath("$.contentHash").value(sha256Hex("detail content")))
				.andExpect(jsonPath("$.observedAt").exists())
				.andExpect(jsonPath("$.normalizedContent").value("detail content"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						otherId, versionId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail")
						.value("Policy version " + versionId + " not found"));
	}

	@Test
	void readsDoNotMutateStateAndRejectBadInput() throws Exception {
		RequestPostProcessor ip = remoteIp("10.0.4.6");
		String token = registerAndLogin("versions-readonly@example.com", ip);
		UUID policyId = registerPolicy(token, ip, "Readonly Policy", "https://readonly.example/privacy");
		observe(policyId, "readonly content");
		UUID versionId = versionRepository.findByPolicy_IdOrderByVersionNumberAsc(policyId)
				.get(0).getId();
		long policiesBefore = policyRepository.count();
		long versionsBefore = versionRepository.count();

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/policies/{policyId}/versions/{versionId}",
						policyId, versionId)
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		assertThat(policyRepository.count()).isEqualTo(policiesBefore);
		assertThat(versionRepository.count()).isEqualTo(versionsBefore);

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", "not-a-uuid")
						.with(ip)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ip))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", policyId)
						.with(ip)
						.queryParam("size", "101")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"));
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

	private void observe(UUID policyId, String... contents) {
		for (String content : contents) {
			observeOne(policyId, content);
		}
	}

	private void observeOne(UUID policyId, String content) {
		versionService.observe(policyId, content, sha256Hex(content));
	}

	private List<JsonNode> versionsOf(MvcResult result) throws Exception {
		JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(root.isArray()).isTrue();
		return objectMapper.convertValue(root,
				objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
	}

	private List<Integer> numbersOf(MvcResult result) throws Exception {
		return versionsOf(result).stream()
				.map(node -> node.get("versionNumber").asInt()).toList();
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
