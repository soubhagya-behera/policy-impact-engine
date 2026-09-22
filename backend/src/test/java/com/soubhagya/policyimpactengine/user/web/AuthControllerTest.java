package com.soubhagya.policyimpactengine.user.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soubhagya.policyimpactengine.user.AuthLoginService;
import com.soubhagya.policyimpactengine.user.AuthRegistrationService;
import com.soubhagya.policyimpactengine.user.DuplicateEmailException;
import com.soubhagya.policyimpactengine.user.RegistrationResult;

/**
 * Phase 8A — web-layer tests for account registration. The application
 * service is mocked; request binding, Bean Validation, status codes,
 * response shape, and RFC 7807 problem responses are verified here.
 * Security filters are disabled: the filter chain itself is covered by
 * {@link SecurityFilterChainTest} with filters enabled.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthRegistrationService service;

	// Phase 8B: AuthController now also depends on the login service;
	// mocked so the registration slice stays isolated.
	@MockitoBean
	private AuthLoginService loginService;

	// Phase 11C: AuthController emits audit events post-commit; mocked
	// so this slice stays isolated from the audit chain.
	@MockitoBean
	private com.soubhagya.policyimpactengine.audit.application.AuditService auditService;

	// Phase 8B: satisfies SecurityConfig wiring in this slice. Filters
	// stay disabled, so the mock never executes.
	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	@Test
	void registerReturnsCreatedResponseWithIdAndEmailOnly() throws Exception {
		UUID id = UUID.randomUUID();
		when(service.register("user@example.com", "correct-horse-1"))
				.thenReturn(new RegistrationResult(id, "user@example.com"));

		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"user@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/auth/users/" + id))
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.email").value("user@example.com"))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());

		verify(service).register("user@example.com", "correct-horse-1");
	}

	@Test
	void registerRejectsInvalidEmail() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"not-an-email","password":"correct-horse-1"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Validation failed"))
				.andExpect(jsonPath("$.errors.email").exists());

		verifyNoInteractions(service);
	}

	@Test
	void registerRejectsShortPassword() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"user@example.com","password":"short-1"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Validation failed"))
				.andExpect(jsonPath("$.errors.password").exists());

		verifyNoInteractions(service);
	}

	@Test
	void duplicateRegistrationReturnsConflictProblem() throws Exception {
		when(service.register("user@example.com", "correct-horse-1"))
				.thenThrow(new DuplicateEmailException("Email is already registered"));

		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"user@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Already registered"))
				.andExpect(jsonPath("$.detail").value("Email is already registered"));
	}
}
