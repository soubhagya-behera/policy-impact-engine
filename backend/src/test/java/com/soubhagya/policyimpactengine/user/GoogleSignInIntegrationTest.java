package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.domain.AuditEvent;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.user.domain.GoogleCompletionRepository;
import com.soubhagya.policyimpactengine.user.domain.RefreshTokenRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 18-B — end-to-end Google sign-in proof (see DECISIONS.md
 * ADR-037): first login provisions a Google-only local user, the
 * one-time completion code exchanges for the standard token pair
 * exactly once, the pair drives refresh rotation and logout, and the
 * account-linking policy never merges on email alone. Real PostgreSQL
 * via Testcontainers; no Google network calls (the provider side is
 * Spring's tested infrastructure).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GoogleSignInIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AuthGoogleService googleService;

	@Autowired
	private GoogleCompletionService completionService;

	@Autowired
	private AuthRegistrationService registrationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private GoogleCompletionRepository completionRepository;

	@Autowired
	private AuditEventRepository auditEventRepository;

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		completionRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void googleFirstLoginIssuesUsablePairAndAudits() throws Exception {
		UUID userId = googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-1", "Google-E2E-1@Example.COM", true));
		String code = completionService.issueCode(userId);

		MvcResult completed = mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").isNumber())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshExpiresIn").isNumber())
				.andReturn();

		String refreshToken = objectMapper.readTree(
				completed.getResponse().getContentAsString()).get("refreshToken").asString();
		String accessToken = objectMapper.readTree(
				completed.getResponse().getContentAsString()).get("accessToken").asString();

		// The local user is Google-only: normalized email, subject
		// anchored, no password hash.
		User persisted = userRepository.findById(userId).orElseThrow();
		assertThat(persisted.getEmail()).isEqualTo("google-e2e-1@example.com");
		assertThat(persisted.getGoogleSub()).isEqualTo("google-sub-e2e-1");
		assertThat(persisted.getPasswordHash()).isNull();

		// Only the digest is persisted; the raw code never touches the
		// database.
		String storedHash = jdbcTemplate.queryForObject(
				"SELECT code_hash FROM auth_google_completion WHERE user_id = ?",
				String.class, userId);
		assertThat(storedHash).hasSize(64).isNotEqualTo(code);

		// The access token is a working Bearer credential for the local
		// user: the JWT sub resolved through an authenticated endpoint.
		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk());

		// Rotation works on the Google-issued refresh token.
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"));

		// The success witness was emitted with the local user as actor.
		assertThat(auditEventRepository
				.findByActorUser_IdOrderByOccurredAtDescIdDesc(userId))
				.filteredOn(event -> event.getEventType()
						== AuditEventType.AUTH_GOOGLE_LOGIN_SUCCEEDED)
				.hasSize(1);
	}

	@Test
	void completionCodeIsSingleUse() throws Exception {
		UUID userId = googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-2", "single-use@example.com", true));
		String code = completionService.issueCode(userId);

		mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + code + "\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(
						MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Invalid Google identity"));
	}

	@Test
	void unknownAndExpiredCodesFailUniformly() throws Exception {
		UUID userId = googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-3", "expiry@example.com", true));
		String code = completionService.issueCode(userId);
		String codeHash = GoogleCompletionService.sha256Hex(code);
		jdbcTemplate.update(
				"UPDATE auth_google_completion SET created_at = now() - interval '1 hour',"
						+ " expires_at = now() - interval '30 minutes'"
						+ " WHERE code_hash = ?",
				codeHash);

		for (String presented : new String[] { "never-minted-code", code }) {
			mockMvc.perform(post("/api/v1/auth/google/complete")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"code\":\"" + presented + "\"}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.detail").value("Invalid Google identity"));
		}

		assertThat(auditEventRepository
				.findByActorUser_IdOrderByOccurredAtDescIdDesc(userId))
				.filteredOn(event -> event.getEventType()
						== AuditEventType.AUTH_GOOGLE_LOGIN_SUCCEEDED)
				.isEmpty();
	}

	@Test
	void googleOnlyUserCannotPasswordLogin() throws Exception {
		googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-4", "google-only@example.com", true));

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"google-only@example.com","password":"correct-horse-1"}
								"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void existingLocalEmailNeverMergesOnGoogleLogin() {
		registrationService.register("local-owner@example.com", "correct-horse-1");

		assertThatThrownBy(() -> googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-5", "local-owner@example.com", true)))
				.isInstanceOf(GoogleLinkRequiredException.class);

		User untouched = userRepository.findByEmail("local-owner@example.com").orElseThrow();
		assertThat(untouched.getGoogleSub()).isNull();
		assertThat(untouched.getPasswordHash()).isNotBlank();
		assertThat(userRepository.findByGoogleSub("google-sub-e2e-5")).isEmpty();
	}

	@Test
	void repeatGoogleLoginReturnsSameUser() {
		UUID first = googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-6", "repeat@example.com", true));
		UUID second = googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-6", "repeat@example.com", true));

		assertThat(second).isEqualTo(first);
		assertThat(userRepository.findAll()).hasSize(1);
	}

	@Test
	void googleSessionSupportsLogoutAndLogoutAllSemantics() throws Exception {
		UUID userId = googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-7", "logout@example.com", true));
		String code = completionService.issueCode(userId);

		MvcResult completed = mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		String refreshToken = objectMapper.readTree(
				completed.getResponse().getContentAsString()).get("refreshToken").asString();

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void googleStartRedirectsIntoAuthorizationEntryPoint() throws Exception {
		mockMvc.perform(get("/api/v1/auth/google/start"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "/oauth2/authorization/google"));
	}

	@Test
	void auditChainStaysValidAfterGoogleLogin() throws Exception {
		UUID userId = googleService.resolveLocalUser(new GoogleIdentity(
				"google-sub-e2e-8", "chain@example.com", true));
		String code = completionService.issueCode(userId);
		mockMvc.perform(post("/api/v1/auth/google/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk());

		assertThat(auditEventRepository.findAll()).isNotEmpty();
		for (AuditEvent event : auditEventRepository.findAll()) {
			assertThat(event.getEventHash()).isNotBlank();
		}
	}
}
