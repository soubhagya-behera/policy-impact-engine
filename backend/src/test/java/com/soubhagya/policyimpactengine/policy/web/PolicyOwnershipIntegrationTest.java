package com.soubhagya.policyimpactengine.policy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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

import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Authenticated policy ownership hardening — end-to-end isolation
 * tests with security filters enabled.
 *
 * <p>Proves registration assigns the authenticated principal as
 * owner, reads and listings are owner-scoped at the repository
 * level, cross-user access behaves as not-found, no client input
 * can choose or transfer ownership, and unauthenticated calls are
 * rejected. Reuses the Phase 8B authentication infrastructure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyOwnershipIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ObjectMapper objectMapper;

	// Phase 11C: registration, login, policy registration, and owner
	// assignment emit audit rows referencing users, so audit rows go
	// first or user deletion violates the actor foreign key.
	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void authenticatedRegistrationAssignsAuthenticatedOwner() throws Exception {
		String tokenA = registerAndLogin("owner-a@example.com");
		String tokenB = registerAndLogin("owner-b@example.com");
		User userA = userFor("owner-a@example.com");

		UUID idA = registerPolicy(tokenA, "A Policy", "https://a.example/privacy");
		UUID idB = registerPolicy(tokenB, "B Policy", "https://b.example/privacy");

		assertThat(policyRepository.findById(idA).orElseThrow().getOwner().getId())
				.isEqualTo(userA.getId());
		assertThat(policyRepository.findById(idB).orElseThrow().getOwner().getId())
				.isEqualTo(userFor("owner-b@example.com").getId());
	}

	@Test
	void clientIdentityInjectionCannotChangeOwnership() throws Exception {
		String tokenA = registerAndLogin("inject-a@example.com");
		registerAndLogin("inject-b@example.com");
		User userA = userFor("inject-a@example.com");
		User userB = userFor("inject-b@example.com");

		MvcResult result = mockMvc.perform(post("/api/v1/policies")
						.header("Authorization", "Bearer " + tokenA)
						.header("X-User-Id", userB.getId().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"A Policy","url":"https://a.example/privacy","userId":"%s","ownerId":"%s"}
								""".formatted(userB.getId(), userB.getId())))
				.andExpect(status().isCreated())
				.andReturn();
		UUID id = UUID.fromString(objectMapper
				.readTree(result.getResponse().getContentAsString()).get("id").asText());

		assertThat(policyRepository.findById(id).orElseThrow().getOwner().getId())
				.isEqualTo(userA.getId());

		Set<String> fields = new HashSet<>(objectMapper
				.readTree(result.getResponse().getContentAsString()).propertyNames());
		assertThat(fields).doesNotContain("userId", "ownerId", "owner");

		mockMvc.perform(get("/api/v1/policies")
						.queryParam("userId", userB.getId().toString())
						.header("X-User-Id", userB.getId().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(id.toString()));
	}

	@Test
	void ownerRetrievesOwnPolicy() throws Exception {
		String token = registerAndLogin("reader@example.com");
		UUID id = registerPolicy(token, "Mine", "https://mine.example/privacy");

		mockMvc.perform(get("/api/v1/policies/{id}", id)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.name").value("Mine"));
	}

	@Test
	void differentUserGets404ForForeignPolicy() throws Exception {
		String tokenA = registerAndLogin("foreign-a@example.com");
		String tokenB = registerAndLogin("foreign-b@example.com");
		UUID idA = registerPolicy(tokenA, "A Policy", "https://a.example/privacy");

		mockMvc.perform(get("/api/v1/policies/{id}", idA)
						.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"))
				.andExpect(jsonPath("$.detail").value("Policy " + idA + " not found"));
	}

	@Test
	void unknownPolicyGets404() throws Exception {
		String token = registerAndLogin("unknown-policy@example.com");
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/policies/{id}", unknown)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void listIsolationShowsOnlyOwnedPoliciesInRegistrationOrder() throws Exception {
		String tokenA = registerAndLogin("list-a@example.com");
		String tokenB = registerAndLogin("list-b@example.com");
		String tokenEmpty = registerAndLogin("list-empty@example.com");
		UUID firstA = registerPolicy(tokenA, "A First", "https://a.example/first");
		UUID secondA = registerPolicy(tokenA, "A Second", "https://a.example/second");
		UUID onlyB = registerPolicy(tokenB, "B Only", "https://b.example/only");

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(firstA.toString()))
				.andExpect(jsonPath("$[1].id").value(secondA.toString()));

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(onlyB.toString()));

		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + tokenEmpty))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void ownershipCannotBeTransferredThroughRest() throws Exception {
		String tokenA = registerAndLogin("transfer-a@example.com");
		registerAndLogin("transfer-b@example.com");
		User userA = userFor("transfer-a@example.com");
		User userB = userFor("transfer-b@example.com");
		UUID id = registerPolicy(tokenA, "A Policy", "https://a.example/privacy");

		// No update endpoint exists: transfer attempts are rejected.
		mockMvc.perform(put("/api/v1/policies/{id}", id)
						.header("Authorization", "Bearer " + tokenA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ownerId\":\"" + userB.getId() + "\"}"))
				.andExpect(status().isMethodNotAllowed());

		assertThat(policyRepository.findById(id).orElseThrow().getOwner().getId())
				.isEqualTo(userA.getId());
	}

	@Test
	void unauthenticatedPolicyCallsReturn401() throws Exception {
		mockMvc.perform(post("/api/v1/policies")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Anon","url":"https://anon.example/privacy"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies/{id}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void malformedPolicyUuidReturns400() throws Exception {
		String token = registerAndLogin("malformed-policy@example.com");

		mockMvc.perform(get("/api/v1/policies/{id}", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request"));
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

	private User userFor(String email) {
		return userRepository.findByEmail(email).orElseThrow();
	}

}
