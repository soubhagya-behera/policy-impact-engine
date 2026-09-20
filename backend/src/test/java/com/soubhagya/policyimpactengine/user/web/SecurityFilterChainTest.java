package com.soubhagya.policyimpactengine.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 8A — filter-chain tests with security filters enabled (no
 * {@code addFilters = false}).
 *
 * <p>Proves the transitional posture: registration is reachable without
 * authentication, existing policy endpoints remain reachable, and an
 * unknown protected endpoint answers 401 as
 * {@code application/problem+json}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityFilterChainTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@BeforeEach
	void clean() {
		userRepository.deleteAll();
	}

	@Test
	void registerEndpointIsReachableWithoutAuthentication() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"chain-user@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("chain-user@example.com"))
				.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain("correct-horse-1");
		assertThat(userRepository.findByEmail("chain-user@example.com")).isPresent();
	}

	@Test
	void duplicateRegistrationThroughChainReturnsConflictProblem() throws Exception {
		String body = """
				{"email":"dup-user@example.com","password":"correct-horse-1"}
				""";
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Already registered"));
	}

	@Test
	void existingPolicyEndpointsRemainReachableWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/policies"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
	}

	@Test
	void unknownEndpointWithoutAuthenticationReturns401Problem() throws Exception {
		mockMvc.perform(get("/api/v1/me/notifications"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}
}
