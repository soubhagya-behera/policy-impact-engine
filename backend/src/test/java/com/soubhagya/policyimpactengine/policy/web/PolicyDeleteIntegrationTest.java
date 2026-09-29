package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 14-C/3 — end-to-end proof for {@code DELETE
 * /api/v1/policies/{policyId}} with security filters enabled (see
 * DECISIONS.md ADR-030): the owner archives through HTTP, repeats
 * stay {@code 204 No Content} with no second audit event, foreign
 * and unknown policies behave as not-found, malformed ids are
 * rejected, anonymous calls are rejected, client identity is
 * ignored, and the archived policy remains readable through the
 * existing owner-scoped reads. Reuses the Phase 8B authentication
 * infrastructure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyDeleteIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.UserRepository userRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void ownerDeleteArchivesPolicyAndEmitsOneAuditEvent() throws Exception {
		String token = registerAndLogin("deleter@example.com");
		UUID id = registerPolicy(token, "Doomed", "https://doomed.example/privacy");

		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(policyRepository.findById(id).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
		List<Map<String, Object>> rows = rowsByType("POLICY_ARCHIVED");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("resource_type")).isEqualTo("POLICY");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(id);
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");
	}

	@Test
	void repeatedDeleteRemainsNoContentWithoutSecondAuditEvent() throws Exception {
		String token = registerAndLogin("repeat-deleter@example.com");
		UUID id = registerPolicy(token, "Twice", "https://twice.example/privacy");

		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(policyRepository.findById(id).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);
	}

	@Test
	void foreignDeleteReturns404AndPreservesActivePolicy() throws Exception {
		String tokenA = registerAndLogin("delete-foreign-a@example.com");
		String tokenB = registerAndLogin("delete-foreign-b@example.com");
		UUID idA = registerPolicy(tokenA, "A Policy", "https://a.example/privacy");

		mockMvc.perform(delete("/api/v1/policies/{policyId}", idA)
						.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail").value("Policy " + idA + " not found"));

		assertThat(policyRepository.findById(idA).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ACTIVE);
		assertThat(countByType("POLICY_ARCHIVED")).isZero();
	}

	@Test
	void unknownDeleteReturns404() throws Exception {
		String token = registerAndLogin("delete-unknown@example.com");
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(delete("/api/v1/policies/{policyId}", unknown)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		assertThat(countByType("POLICY_ARCHIVED")).isZero();
	}

	@Test
	void malformedDeleteUuidReturns400() throws Exception {
		String token = registerAndLogin("delete-malformed@example.com");

		mockMvc.perform(delete("/api/v1/policies/{policyId}", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		assertThat(countByType("POLICY_ARCHIVED")).isZero();
	}

	@Test
	void anonymousDeleteReturns401() throws Exception {
		mockMvc.perform(delete("/api/v1/policies/{policyId}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void clientIdentityInjectionCannotArchiveForeignPolicy() throws Exception {
		String tokenA = registerAndLogin("delete-inject-a@example.com");
		String tokenB = registerAndLogin("delete-inject-b@example.com");
		UUID idA = registerPolicy(tokenA, "A Policy", "https://a.example/privacy");
		UUID idB = registerPolicy(tokenB, "B Policy", "https://b.example/privacy");

		mockMvc.perform(delete("/api/v1/policies/{policyId}", idB)
						.queryParam("userId", UUID.randomUUID().toString())
						.header("X-User-Id", UUID.randomUUID().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound());

		assertThat(policyRepository.findById(idB).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ACTIVE);

		mockMvc.perform(delete("/api/v1/policies/{policyId}", idA)
						.queryParam("userId", UUID.randomUUID().toString())
						.header("X-User-Id", UUID.randomUUID().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNoContent());

		assertThat(policyRepository.findById(idA).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
		assertThat(countByType("POLICY_ARCHIVED")).isEqualTo(1);
	}

	@Test
	void archivedPolicyRemainsReadableThroughExistingReads() throws Exception {
		String token = registerAndLogin("delete-readable@example.com");
		UUID id = registerPolicy(token, "Kept", "https://kept.example/privacy");

		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/policies/{id}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("ARCHIVED"));

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		mockMvc.perform(get("/api/v1/policies/{policyId}/versions", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/policies/{policyId}/changes", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
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

	private int countByType(String eventType) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ?",
				Integer.class, eventType);
		return count == null ? 0 : count;
	}

	private List<Map<String, Object>> rowsByType(String eventType) {
		return jdbcTemplate.queryForList("SELECT actor_user_id, resource_type, resource_id, "
				+ "metadata FROM audit_event WHERE event_type = ? ORDER BY occurred_at, id",
				eventType);
	}
}
