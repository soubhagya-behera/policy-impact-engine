package com.soubhagya.policyimpactengine.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Phase 1F — Final integration / acceptance tests for the Policy registration
 * vertical slice, extended by authenticated policy hardening: every call
 * carries a Bearer token and the persisted owner equals the principal.
 *
 * <p>Exercises the real application layers together:
 * HTTP → Security → Controller → Validation → Service → URL Validator → Repository → PostgreSQL (Testcontainers).
 * No mocks for PolicyService or PolicyRepository. Flyway migrates the schema and
 * Hibernate validates it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyRegistrationIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PolicyRepository repository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ObjectMapper objectMapper;

	// Phase 11C: registration, login, and policy registration emit
	// audit rows referencing users, so audit rows go first or user
	// deletion violates the actor foreign key.
	@Autowired
	private com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository auditEventRepository;

	@BeforeEach
	void cleanDatabase() {
		auditEventRepository.deleteAll();
		repository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void fullVerticalSlice_successfulRegistration_retrieveById_retrieveCollection() throws Exception {
		String token = registerAndLogin("owner@example.com");
		User owner = userRepository.findByEmail("owner@example.com").orElseThrow();
		// 1. Successful registration with trimmed URL
		String rawUrl = "  https://example.com/privacy  ";
		String expectedNormalizedUrl = "https://example.com/privacy";
		String policyName = "Acme Privacy Policy";

		MvcResult postResult = mockMvc.perform(post("/api/v1/policies")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"%s","url":"%s"}
								""".formatted(policyName, rawUrl)))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").isString())
				.andExpect(jsonPath("$.name").value(policyName))
				.andExpect(jsonPath("$.url").value(expectedNormalizedUrl))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.updatedAt").isNotEmpty())
				.andReturn();

		String responseBody = postResult.getResponse().getContentAsString();
		String idString = extractId(responseBody);
		assertThat(idString).isNotBlank();

		// verify generated UUID is valid
		UUID generatedId = UUID.fromString(idString);
		assertThat(generatedId).isNotNull();

		// verify persisted state directly via repository
		assertThat(repository.count()).isEqualTo(1);
		var persisted = repository.findById(generatedId).orElseThrow();
		assertThat(persisted.getName()).isEqualTo(policyName);
		assertThat(persisted.getUrl()).isEqualTo(expectedNormalizedUrl);
		assertThat(persisted.getStatus().name()).isEqualTo("ACTIVE");
		assertThat(persisted.getCreatedAt()).isNotNull();
		assertThat(persisted.getUpdatedAt()).isNotNull();
		assertThat(persisted.getOwner()).isNotNull();
		assertThat(persisted.getOwner().getId()).isEqualTo(owner.getId());

		// 2. Retrieve the created policy by ID
		mockMvc.perform(get("/api/v1/policies/{id}", generatedId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").value(generatedId.toString()))
				.andExpect(jsonPath("$.name").value(policyName))
				.andExpect(jsonPath("$.url").value(expectedNormalizedUrl))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.updatedAt").isNotEmpty());

		// 3. Retrieve policy collection and verify created policy is present
		mockMvc.perform(get("/api/v1/policies")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(generatedId.toString()))
				.andExpect(jsonPath("$[0].name").value(policyName))
				.andExpect(jsonPath("$[0].url").value(expectedNormalizedUrl));
	}

	@Test
	void unauthenticatedPolicyCallsReturn401() throws Exception {
		mockMvc.perform(post("/api/v1/policies")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Acme Privacy Policy","url":"https://example.com/privacy"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(get("/api/v1/policies/{id}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void invalidRegistration_httpUrl_returnsProblemJsonAndDoesNotPersist() throws Exception {
		String token = registerAndLogin("bad-url@example.com");
		long countBefore = repository.count();

		mockMvc.perform(post("/api/v1/policies")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Invalid Policy","url":"http://example.com/privacy"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").exists())
				.andExpect(jsonPath("$.detail").exists());

		assertThat(repository.count()).isEqualTo(countBefore);
	}

	@Test
	void beanValidationFailure_blankName_returnsProblemJson() throws Exception {
		String token = registerAndLogin("blank-name@example.com");
		long countBefore = repository.count();

		mockMvc.perform(post("/api/v1/policies")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"","url":"https://example.com/privacy"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Validation failed"))
				.andExpect(jsonPath("$.errors.name").exists());

		assertThat(repository.count()).isEqualTo(countBefore);
	}

	@Test
	void beanValidationFailure_blankUrl_returnsProblemJson() throws Exception {
		String token = registerAndLogin("blank-url@example.com");
		long countBefore = repository.count();

		mockMvc.perform(post("/api/v1/policies")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Acme Policy","url":""}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(repository.count()).isEqualTo(countBefore);
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

	private String extractId(String json) {
		// minimal extraction without ObjectMapper: "id":"<uuid>"
		int idx = json.indexOf("\"id\"");
		if (idx < 0) {
			return "";
		}
		int colon = json.indexOf(':', idx);
		int firstQuote = json.indexOf('"', colon);
		int secondQuote = json.indexOf('"', firstQuote + 1);
		if (firstQuote < 0 || secondQuote < 0) {
			return "";
		}
		return json.substring(firstQuote + 1, secondQuote);
	}
}
