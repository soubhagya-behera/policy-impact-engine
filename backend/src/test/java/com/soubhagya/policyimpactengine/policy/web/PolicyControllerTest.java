package com.soubhagya.policyimpactengine.policy.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyResponse;

/**
 * Web-layer tests for the policy registration API. The application service
 * is mocked; request binding, Bean Validation, status codes, and response
 * shape are verified here. Security filters are disabled: authentication
 * arrives in a later phase.
 */
@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PolicyService service;

	@Test
	void registerReturnsCreatedPolicy() throws Exception {
		PolicyResponse response = sampleResponse();
		when(service.register("Acme Privacy Policy", "https://example.com/privacy")).thenReturn(response);

		mockMvc.perform(post("/api/v1/policies")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Acme Privacy Policy","url":"https://example.com/privacy"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/policies/" + response.id()))
				.andExpect(jsonPath("$.id").value(response.id().toString()))
				.andExpect(jsonPath("$.name").value("Acme Privacy Policy"))
				.andExpect(jsonPath("$.url").value("https://example.com/privacy"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		verify(service).register("Acme Privacy Policy", "https://example.com/privacy");
	}

	@Test
	void registerRejectsBlankName() throws Exception {
		mockMvc.perform(post("/api/v1/policies")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"","url":"https://example.com/privacy"}
								"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void registerRejectsBlankUrl() throws Exception {
		mockMvc.perform(post("/api/v1/policies")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Acme Privacy Policy","url":""}
								"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void registerRejectsOversizedName() throws Exception {
		String oversized = "n".repeat(256);

		mockMvc.perform(post("/api/v1/policies")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"%s","url":"https://example.com/privacy"}
								""".formatted(oversized)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void listReturnsPolicies() throws Exception {
		PolicyResponse response = sampleResponse();
		when(service.list()).thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/policies"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(response.id().toString()))
				.andExpect(jsonPath("$[0].name").value("Acme Privacy Policy"));

		verify(service).list();
	}

	@Test
	void getByIdReturnsPolicy() throws Exception {
		PolicyResponse response = sampleResponse();
		when(service.getById(response.id())).thenReturn(response);

		mockMvc.perform(get("/api/v1/policies/{id}", response.id()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(response.id().toString()))
				.andExpect(jsonPath("$.url").value("https://example.com/privacy"));

		verify(service).getById(response.id());
	}

	@Test
	void getByIdRejectsMalformedIdentifier() throws Exception {
		mockMvc.perform(get("/api/v1/policies/{id}", "not-a-uuid"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	private PolicyResponse sampleResponse() {
		return new PolicyResponse(
				UUID.randomUUID(),
				"Acme Privacy Policy",
				"https://example.com/privacy",
				"ACTIVE",
				Instant.parse("2026-09-14T10:00:00Z"),
				Instant.parse("2026-09-14T10:00:00Z"));
	}

}
