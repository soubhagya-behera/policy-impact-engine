package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.soubhagya.policyimpactengine.audit.application.AuditVerificationService;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttemptRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 16-A/2 — end-to-end proof for {@code POST
 * /api/v1/policies/{policyId}/reactivate} with security filters enabled
 * (see DECISIONS.md ADR-033): the owner reactivates through HTTP,
 * repeats stay {@code 204 No Content} with no second audit event,
 * foreign and unknown policies behave as not-found, malformed ids are
 * rejected, anonymous calls are rejected, and reactivation itself
 * triggers no observation — the policy simply rejoins the unchanged
 * scheduler selection with its frozen check timestamp intact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyReactivationIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyFetchAttemptRepository attemptRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AuditVerificationService verificationService;

	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private com.soubhagya.policyimpactengine.user.domain.UserRepository userRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		attemptRepository.deleteAll();
		policyRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void ownerReactivateRestoresActiveAndEmitsOneAuditEvent() throws Exception {
		String token = registerAndLogin("reactivator@example.com");
		UUID id = registerPolicy(token, "Paused", "https://paused.example/privacy");
		archivePolicy(token, id);

		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(policyRepository.findById(id).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ACTIVE);
		List<Map<String, Object>> rows = rowsByType("POLICY_REACTIVATED");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("resource_type")).isEqualTo("POLICY");
		assertThat(rows.get(0).get("resource_id")).isEqualTo(id);
		assertThat(rows.get(0).get("metadata")).isEqualTo("{}");

		mockMvc.perform(get("/api/v1/policies/{id}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void repeatedReactivateRemainsNoContentWithoutSecondAuditEvent() throws Exception {
		String token = registerAndLogin("repeat-reactivator@example.com");
		UUID id = registerPolicy(token, "Twice", "https://twice.example/privacy");
		archivePolicy(token, id);
		reactivatePolicy(token, id);

		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(countByType("POLICY_REACTIVATED")).isEqualTo(1);
	}

	@Test
	void reactivateOnActivePolicyIsSilentNoOp() throws Exception {
		String token = registerAndLogin("already-active@example.com");
		UUID id = registerPolicy(token, "Live", "https://live.example/privacy");

		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		assertThat(countByType("POLICY_REACTIVATED")).isZero();
		assertThat(policyRepository.findById(id).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ACTIVE);
	}

	@Test
	void foreignUnknownMalformedAndAnonymousReactivateRejected() throws Exception {
		String token = registerAndLogin("reactivate-owner@example.com");
		String stranger = registerAndLogin("reactivate-stranger@example.com");
		UUID id = registerPolicy(token, "Mine", "https://mine.example/privacy");
		archivePolicy(token, id);
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", id)
						.header("Authorization", "Bearer " + stranger))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", unknown)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", id))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", id)
						.header("Authorization", "Bearer invalid.token.here"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		assertThat(countByType("POLICY_REACTIVATED")).isZero();
		assertThat(policyRepository.findById(id).orElseThrow().getStatus())
				.isEqualTo(PolicyStatus.ARCHIVED);
	}

	@Test
	void reactivationTriggersNoObservationAndPreservesSchedule() throws Exception {
		String token = registerAndLogin("reactivate-quiet@example.com");
		UUID id = registerPolicy(token, "Quiet", "https://quiet.example/privacy");
		Instant frozen = policyRepository.findById(id).orElseThrow().getNextCheckAt();
		archivePolicy(token, id);
		assertThat(policyRepository.findById(id).orElseThrow().getNextCheckAt())
				.isEqualTo(frozen);

		reactivatePolicy(token, id);

		assertThat(policyRepository.findById(id).orElseThrow().getNextCheckAt())
				.isEqualTo(frozen);
		assertThat(attemptRepository.count()).isZero();
		assertThat(policyRepository
				.findByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAscIdAsc(
						PolicyStatus.ACTIVE, Instant.now()))
				.extracting(policy -> policy.getId().toString())
				.contains(id.toString());
		assertThat(verificationService.verify().isValid()).isTrue();
	}

	private void archivePolicy(String token, UUID id) throws Exception {
		mockMvc.perform(delete("/api/v1/policies/{policyId}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());
	}

	private void reactivatePolicy(String token, UUID id) throws Exception {
		mockMvc.perform(post("/api/v1/policies/{policyId}/reactivate", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
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
