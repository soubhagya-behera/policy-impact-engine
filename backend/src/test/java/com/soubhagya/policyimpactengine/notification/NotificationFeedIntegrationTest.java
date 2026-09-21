package com.soubhagya.policyimpactengine.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.notification.domain.Notification;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.JwtProperties;
import com.soubhagya.policyimpactengine.user.JwtService;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 10B-2B — end-to-end tests for the authenticated notification
 * REST feed with security filters enabled.
 *
 * <p>Exercises the real flow (register → login → Bearer) against
 * Testcontainers PostgreSQL and proves user isolation, newest-first
 * ordering, idempotent mark-read, and the exact 401/404/400 behavior.
 * No authentication mechanism is created here; the Phase 8B
 * infrastructure is reused unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationFeedIntegrationTest {

	private static final Instant OLDER = Instant.parse("2026-09-18T10:00:00Z");
	private static final Instant NEWER = Instant.parse("2026-09-18T12:00:00Z");

	private static final Set<String> APPROVED_FIELDS = Set.of(
			"id", "assessmentId", "policyId", "versionNumber",
			"createdAt", "readAt", "read");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private ImpactAssessmentRepository assessmentRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		notificationRepository.deleteAll();
		assessmentRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void userSeesOwnNotificationsNewestFirst() throws Exception {
		String token = registerAndLogin("feed-a@example.com");
		User user = userFor("feed-a@example.com");
		Crafted older = craftNotification(user, OLDER);
		Crafted newer = craftNotification(user, NEWER);

		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(newer.notification().getId().toString()))
				.andExpect(jsonPath("$[1].id").value(older.notification().getId().toString()))
				.andExpect(jsonPath("$[0].assessmentId")
						.value(newer.assessment().getId().toString()))
				.andExpect(jsonPath("$[0].policyId")
						.value(newer.policy().getId().toString()))
				.andExpect(jsonPath("$[0].versionNumber").value(2))
				.andExpect(jsonPath("$[0].read").value(false));
	}

	@Test
	void userDoesNotSeeOtherUsersNotifications() throws Exception {
		String tokenA = registerAndLogin("iso-a@example.com");
		registerAndLogin("iso-b@example.com");
		User userB = userFor("iso-b@example.com");
		craftNotification(userB, OLDER);

		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void markReadOfForeignNotificationReturns404() throws Exception {
		String tokenA = registerAndLogin("cross-a@example.com");
		registerAndLogin("cross-b@example.com");
		User userB = userFor("cross-b@example.com");
		Crafted foreign = craftNotification(userB, OLDER);

		mockMvc.perform(post("/api/v1/me/notifications/{id}/read", foreign.notification().getId())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void unknownNotificationReturns404() throws Exception {
		String token = registerAndLogin("unknown@example.com");

		mockMvc.perform(post("/api/v1/me/notifications/{id}/read", UUID.randomUUID())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Resource not found"));
	}

	@Test
	void clientSuppliedUserIdCannotOverrideJwtPrincipal() throws Exception {
		String tokenA = registerAndLogin("override-a@example.com");
		registerAndLogin("override-b@example.com");
		User userA = userFor("override-a@example.com");
		User userB = userFor("override-b@example.com");
		Crafted own = craftNotification(userA, OLDER);
		craftNotification(userB, NEWER);

		// Query parameter override attempt: the feed still shows only A's row.
		mockMvc.perform(get("/api/v1/me/notifications")
						.queryParam("userId", userB.getId().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(own.notification().getId().toString()));

		// Custom header override attempt: ignored the same way.
		mockMvc.perform(get("/api/v1/me/notifications")
						.header("X-User-Id", userB.getId().toString())
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(own.notification().getId().toString()));

		// Body override attempt: the mark-read still operates as A.
		mockMvc.perform(post("/api/v1/me/notifications/{id}/read", own.notification().getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"" + userB.getId() + "\"}")
						.header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.read").value(true));
	}

	@Test
	void missingTokenReturns401Problem() throws Exception {
		mockMvc.perform(get("/api/v1/me/notifications"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(post("/api/v1/me/notifications/{id}/read", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void invalidMalformedWrongSignatureAndExpiredTokensReturn401() throws Exception {
		registerAndLogin("token-user@example.com");
		User user = userFor("token-user@example.com");

		String[] rejected = {
				"Bearer invalid.token.here",
				"Bearer not-a-jwt",
				"Token abc",
				"Bearer " + wrongSignatureToken(user.getId()),
				"Bearer " + expiredToken(user.getId())
		};
		for (String header : rejected) {
			mockMvc.perform(get("/api/v1/me/notifications").header("Authorization", header))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.title").value("Unauthenticated"));
		}
	}

	@Test
	void validJwtWithNoNotificationsReturnsEmptyArray() throws Exception {
		String token = registerAndLogin("empty@example.com");

		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/api/v1/me/notifications/unread")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void unreadEndpointExcludesReadNotifications() throws Exception {
		String token = registerAndLogin("unread@example.com");
		User user = userFor("unread@example.com");
		Crafted older = craftNotification(user, OLDER);
		Crafted newer = craftNotification(user, NEWER);

		mockMvc.perform(post("/api/v1/me/notifications/{id}/read", newer.notification().getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.read").value(true));

		mockMvc.perform(get("/api/v1/me/notifications/unread")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(older.notification().getId().toString()));

		mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void markReadIsIdempotent() throws Exception {
		String token = registerAndLogin("idempotent@example.com");
		User user = userFor("idempotent@example.com");
		Crafted crafted = craftNotification(user, OLDER);

		MvcResult first = mockMvc.perform(
						post("/api/v1/me/notifications/{id}/read", crafted.notification().getId())
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.read").value(true))
				.andReturn();
		String firstReadAt = objectMapper.readTree(first.getResponse().getContentAsString())
				.get("readAt").asText();
		assertThat(firstReadAt).isNotBlank();

		MvcResult second = mockMvc.perform(
						post("/api/v1/me/notifications/{id}/read", crafted.notification().getId())
								.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.read").value(true))
				.andReturn();
		String secondReadAt = objectMapper.readTree(second.getResponse().getContentAsString())
				.get("readAt").asText();

		// PostgreSQL timestamptz rounds sub-microsecond values on
		// persist, while the first response carries the in-memory
		// nanosecond Clock instant. Rounding the first instant
		// explicitly (plusNanos(500) then truncate) models the database
		// precision deterministically: exact micro equality then proves
		// the second POST changed nothing, with no tolerance window.
		Instant firstRounded = Instant.parse(firstReadAt).plusNanos(500)
				.truncatedTo(ChronoUnit.MICROS);
		Instant persisted = notificationRepository.findById(crafted.notification().getId())
				.orElseThrow().getReadAt();
		assertThat(persisted).isEqualTo(firstRounded);
		assertThat(Instant.parse(secondReadAt)).isEqualTo(persisted);
	}

	@Test
	void malformedNotificationUuidReturns400Problem() throws Exception {
		String token = registerAndLogin("malformed@example.com");

		mockMvc.perform(post("/api/v1/me/notifications/{id}/read", "not-a-uuid")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Malformed request"));
	}

	@Test
	void responseExposesOnlyApprovedFields() throws Exception {
		String token = registerAndLogin("shape@example.com");
		User user = userFor("shape@example.com");
		craftNotification(user, OLDER);

		MvcResult feed = mockMvc.perform(get("/api/v1/me/notifications")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		Set<String> feedFields = fieldNames(
				objectMapper.readTree(feed.getResponse().getContentAsString()).get(0));
		assertThat(feedFields).isEqualTo(APPROVED_FIELDS);

		MvcResult unread = mockMvc.perform(get("/api/v1/me/notifications/unread")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(fieldNames(
				objectMapper.readTree(unread.getResponse().getContentAsString()).get(0)))
				.isEqualTo(APPROVED_FIELDS);
	}

	private Set<String> fieldNames(tools.jackson.databind.JsonNode node) {
		return new HashSet<>(node.propertyNames());
	}

	private String wrongSignatureToken(UUID userId) {
		JwtService otherKey = new JwtService(
				new JwtProperties("different-test-secret-that-is-long-enough-0000", null),
				Clock.systemUTC());
		return otherKey.issueAccessToken(userId);
	}

	private String expiredToken(UUID userId) {
		JwtService pastIssuer = new JwtService(
				jwtProperties, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
		return pastIssuer.issueAccessToken(userId);
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

	private Crafted craftNotification(User user, Instant createdAt) {
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		String oldText = "old-" + UUID.randomUUID();
		String newText = "new-" + UUID.randomUUID();
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, oldText, "hash-old-" + UUID.randomUUID()));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, newText, "hash-new-" + UUID.randomUUID()));
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(
				new ImpactAssessment(user, v2, v1, 80, ImpactBand.HIGH, 1));
		Notification notification = notificationRepository.saveAndFlush(
				new Notification(assessment, createdAt));
		return new Crafted(policy, v1, v2, assessment, notification);
	}

	private record Crafted(Policy policy, PolicyVersion v1, PolicyVersion v2,
			ImpactAssessment assessment, Notification notification) {
	}

}
