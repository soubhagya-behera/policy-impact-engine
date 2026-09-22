package com.soubhagya.policyimpactengine.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.user.UserPrivacyPreferenceService;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 11D — filters-enabled integration tests for {@code
 * GET /api/v1/me/audit-events} against real PostgreSQL with real
 * JWT authentication. Proves the user-scoped, newest-first,
 * read-only feed: isolation, ordering, verbatim metadata/hashes,
 * the exact eight-field shape, all six 11C event types, and that
 * reads never write.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditEventFeedIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private MockMvc mockMvc;
	@Autowired private UserRepository userRepository;
	@Autowired private PolicyRepository policyRepository;
	@Autowired private PolicyService policyService;
	@Autowired private UserPrivacyPreferenceService preferenceService;
	@Autowired private UserPrivacyPreferenceRepository preferenceRepository;
	@Autowired private AuditService auditService;
	@Autowired private JwtService jwtService;
	@Autowired private AuditEventRepository auditEventRepository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		// 11C rule: audit rows reference users, so audit goes first.
		auditEventRepository.deleteAll();
		preferenceRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void unauthenticatedGetReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/me/audit-events"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void garbageAndTamperedTokensReturn401() throws Exception {
		mockMvc.perform(get("/api/v1/me/audit-events")
						.header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized());
		String token = registerAndLogin("tamper@example.com");
		mockMvc.perform(get("/api/v1/me/audit-events")
						.header("Authorization", "Bearer " + token + "tampered"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void emptyFeedReturnsEmptyArray() throws Exception {
		// A repository-created user has no audit history: registration
		// and login would themselves emit, so the token is issued
		// directly for a user that never acted.
		User user = userRepository.saveAndFlush(new User());
		String token = jwtService.issueAccessToken(user.getId());

		mockMvc.perform(get("/api/v1/me/audit-events")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(content().json("[]"));
	}

	@Test
	void usersSeeOnlyTheirOwnEvents() throws Exception {
		String tokenA = registerAndLogin("feed-a@example.com");
		String tokenB = registerAndLogin("feed-b@example.com");
		registerPolicy(tokenA, "A Policy", "https://a.example/privacy");

		String feedA = feed(tokenA);
		String feedB = feed(tokenB);

		assertThat(typesIn(feedA)).containsExactlyInAnyOrder(
				"AUTH_USER_REGISTERED", "AUTH_LOGIN_SUCCEEDED", "POLICY_REGISTERED");
		assertThat(typesIn(feedB)).containsExactlyInAnyOrder(
				"AUTH_USER_REGISTERED", "AUTH_LOGIN_SUCCEEDED");
		assertThat(idsIn(feedA)).doesNotContainAnyElementsOf(idsIn(feedB));
		assertThat(policyIdsIn(feedA)).hasSize(1);
		assertThat(policyIdsIn(feedB)).isEmpty();
	}

	@Test
	void feedIsNewestFirst() throws Exception {
		String token = registerAndLogin("order@example.com");
		registerPolicy(token, "Order Policy", "https://order.example/privacy");

		assertThat(typesIn(feed(token))).containsExactly(
				"POLICY_REGISTERED", "AUTH_LOGIN_SUCCEEDED", "AUTH_USER_REGISTERED");
	}

	@Test
	void sameInstantTieBreaksByIdDesc() throws Exception {
		String token = registerAndLogin("tie@example.com");
		User user = userFor("tie@example.com");
		Instant at = Instant.parse("2026-09-18T10:00:00Z");
		auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null,
				null, at);
		auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null,
				null, at.plusNanos(500));

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT id, event_hash FROM audit_event WHERE actor_user_id = ? "
						+ "AND event_type = 'AUTH_LOGIN_SUCCEEDED' AND occurred_at = ? "
						+ "ORDER BY occurred_at, id",
				user.getId(), java.sql.Timestamp.from(at));
		// The nanosecond difference is truncated to microseconds on
		// persist, so both rows share one instant: id DESC decides.
		// The login event is newer, so the tied pair sits behind it.
		assertThat(rows).hasSize(2);
		List<String> tied = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(feed(token))) {
			if (item.get("occurredAt").asText().startsWith("2026-09-18T10:00:00")) {
				tied.add(item.get("id").asText());
			}
		}
		assertThat(tied).containsExactly(
				rows.get(1).get("id").toString(), rows.get(0).get("id").toString());
	}

	@Test
	void feedItemsHaveExactlyEightSafeFields() throws Exception {
		String token = registerAndLogin("shape@example.com");
		updatePreferences(token, "{\"LOCATION\":5}");

		String body = feed(token);
		List<Map<String, Object>> items = objectMapper.readValue(body,
				new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
				});
		assertThat(items.size()).isGreaterThanOrEqualTo(3);
		for (Map<String, Object> item : items) {
			assertThat(item.keySet()).containsExactlyInAnyOrder("id", "occurredAt",
					"eventType", "resourceType", "resourceId", "metadata", "prevHash",
					"eventHash");
		}
		assertThat(body).doesNotContain("passwordHash");
		assertThat(body).doesNotContain("accessToken");
		assertThat(body).doesNotContain("Authorization");
		assertThat(body).doesNotContain("actorUser");
		assertThat(body).doesNotContain("actor_user_id");
		assertThat(body).doesNotContain("secret");
		assertThat(body).doesNotContain("email");
	}

	@Test
	void metadataAndHashesAreReturnedVerbatim() throws Exception {
		registerAndLogin("verbatim@example.com");
		User user = userFor("verbatim@example.com");
		Instant at = Instant.parse("2026-09-18T10:00:00Z");
		String metadata = "{\"conceptCode\":\"LOCATION\",\"oldSensitivity\":null,"
				+ "\"newSensitivity\":5}";
		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		fields.put("conceptCode", "LOCATION");
		fields.put("oldSensitivity", null);
		fields.put("newSensitivity", 5);
		auditService.append(user.getId(), AuditEventType.PRIVACY_PREFERENCE_UPSERTED,
				"USER", user.getId(), AuditMetadata.of(fields), at);
		auditService.append(user.getId(), AuditEventType.AUTH_LOGIN_SUCCEEDED, null, null,
				null, at.plusSeconds(1));

		Map<String, Object> persisted = jdbcTemplate.queryForMap(
				"SELECT metadata, prev_hash, event_hash FROM audit_event "
						+ "WHERE actor_user_id = ? AND event_type = 'PRIVACY_PREFERENCE_UPSERTED'",
				user.getId());
		String token = login("verbatim@example.com");
		JsonNode feed = objectMapper.readTree(feed(token));
		JsonNode upserted = null;
		JsonNode nullMetadata = null;
		for (JsonNode item : feed) {
			if (item.get("eventType").asText().equals("PRIVACY_PREFERENCE_UPSERTED")) {
				upserted = item;
			}
			if (item.get("eventType").asText().equals("AUTH_LOGIN_SUCCEEDED")
					&& item.get("metadata").isNull()) {
				nullMetadata = item;
			}
		}

		assertThat(upserted).isNotNull();
		assertThat(upserted.get("metadata").asText()).isEqualTo(metadata);
		assertThat(upserted.get("metadata").asText()).isEqualTo(persisted.get("metadata"));
		assertThat(upserted.get("prevHash").asText()).isEqualTo(persisted.get("prev_hash"));
		assertThat(upserted.get("eventHash").asText()).isEqualTo(persisted.get("event_hash"));
		assertThat(nullMetadata).isNotNull();
		assertThat(nullMetadata.get("resourceType").isNull()).isTrue();
		assertThat(nullMetadata.get("resourceId").isNull()).isTrue();
	}

	@Test
	void queryUserIdCannotChangeOwnership() throws Exception {
		String tokenA = registerAndLogin("qa@example.com");
		String tokenB = registerAndLogin("qb@example.com");
		User userB = userFor("qb@example.com");

		String body = mockMvc.perform(get("/api/v1/me/audit-events")
						.header("Authorization", "Bearer " + tokenA)
						.queryParam("userId", userB.getId().toString()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(typesIn(body)).containsExactlyInAnyOrder(
				"AUTH_USER_REGISTERED", "AUTH_LOGIN_SUCCEEDED");
		assertThat(idsIn(body)).doesNotContainAnyElementsOf(idsIn(feed(tokenB)));
	}

	@Test
	void getCreatesNoAuditEvent() throws Exception {
		String token = registerAndLogin("readonly@example.com");
		updatePreferences(token, "{\"LOCATION\":5}");
		int before = countAll();

		mockMvc.perform(get("/api/v1/me/audit-events")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/me/audit-events")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		assertThat(countAll()).isEqualTo(before);
	}

	@Test
	void allSixEventTypesAreObservableForTheCorrectUser() throws Exception {
		String token = registerAndLogin("six@example.com");
		User user = userFor("six@example.com");
		registerPolicy(token, "Six Policy", "https://six.example/privacy");
		Policy unowned = policyRepository
				.saveAndFlush(new Policy("Unowned", "https://unowned.example/privacy"));
		updatePreferences(token, "{\"LOCATION\":5}");
		updatePreferences(token, "{\"LOCATION\":2}");

		assertThat(policyService.assignOwner(user.getId(), unowned.getId())).isTrue();
		preferenceService.deletePreference(user.getId(), "LOCATION");

		String body = feed(token);
		assertThat(typesIn(body)).contains(
				"AUTH_USER_REGISTERED", "AUTH_LOGIN_SUCCEEDED", "POLICY_REGISTERED",
				"POLICY_OWNER_ASSIGNED", "PRIVACY_PREFERENCE_UPSERTED",
				"PRIVACY_PREFERENCE_DELETED");
		JsonNode feed = objectMapper.readTree(body);
		for (JsonNode item : feed) {
			if (item.get("eventType").asText().equals("POLICY_OWNER_ASSIGNED")) {
				assertThat(item.get("resourceId").asText()).isEqualTo(unowned.getId().toString());
			}
		}
	}

	private String feed(String token) throws Exception {
		return mockMvc.perform(get("/api/v1/me/audit-events")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andReturn().getResponse().getContentAsString();
	}

	private String registerAndLogin(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"correct-horse-1"}
								""".formatted(email)))
				.andExpect(status().isCreated());
		return login(email);
	}

	private String login(String email) throws Exception {
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

	private User userFor(String email) {
		return userRepository.findByEmail(email).orElseThrow();
	}

	private void registerPolicy(String token, String name, String url) throws Exception {
		mockMvc.perform(post("/api/v1/policies")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"%s","url":"%s"}
								""".formatted(name, url)))
				.andExpect(status().isCreated());
	}

	private void updatePreferences(String token, String preferencesJson) throws Exception {
		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":%s}
								""".formatted(preferencesJson)))
				.andExpect(status().isOk());
	}

	private List<String> typesIn(String body) throws Exception {
		List<String> types = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(body)) {
			types.add(item.get("eventType").asText());
		}
		return types;
	}

	private List<String> idsIn(String body) throws Exception {
		List<String> ids = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(body)) {
			ids.add(item.get("id").asText());
		}
		return ids;
	}

	private List<String> policyIdsIn(String body) throws Exception {
		List<String> ids = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(body)) {
			if (item.get("eventType").asText().equals("POLICY_REGISTERED")) {
				ids.add(item.get("resourceId").asText());
			}
		}
		return ids;
	}

	private int countAll() {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event",
				Integer.class);
		return count == null ? 0 : count;
	}
}
