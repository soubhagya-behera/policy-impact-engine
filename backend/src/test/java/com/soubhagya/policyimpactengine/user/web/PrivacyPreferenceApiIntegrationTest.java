package com.soubhagya.policyimpactengine.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Authenticated privacy-preference API — end-to-end tests with
 * security filters enabled.
 *
 * <p>Proves the complete concept surface, validation, user
 * isolation, idempotent updates, persistence, and that a persisted
 * preference actually flows into the existing personalized impact
 * calculation. Reuses the Phase 8B authentication infrastructure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PrivacyPreferenceApiIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PrivacyConceptRepository conceptRepository;

	@Autowired
	private UserPrivacyPreferenceRepository preferenceRepository;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private PolicyChangeRecordRepository changeRepository;

	@Autowired
	private ChangeConceptMatchRepository matchRepository;

	@Autowired
	private ChangeImpactRepository impactRepository;

	@Autowired
	private ImpactAssessmentRepository assessmentRepository;

	@Autowired
	private ImpactAssessmentBreakdownRepository breakdownRepository;

	@Autowired
	private ImpactAssessmentService impactAssessmentService;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		breakdownRepository.deleteAll();
		assessmentRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		preferenceRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void unauthenticatedCallsReturn401() throws Exception {
		mockMvc.perform(get("/api/v1/me/privacy-preferences"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":3}}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Unauthenticated"));
	}

	@Test
	void getReturnsCompleteConceptSurfaceWithDefaults() throws Exception {
		String token = registerAndLogin("surface@example.com");
		List<String> expectedCodes = conceptRepository.findAll().stream()
				.map(PrivacyConcept::getCode).sorted().toList();
		assertThat(expectedCodes).isNotEmpty();

		MvcResult result = mockMvc.perform(get("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(expectedCodes.size()))
				.andReturn();

		List<String> actualCodes = new ArrayList<>();
		for (int i = 0; i < expectedCodes.size(); i++) {
			String code = objectMapper.readTree(result.getResponse().getContentAsString())
					.get(i).get("conceptCode").asText();
			actualCodes.add(code);
			var item = objectMapper.readTree(result.getResponse().getContentAsString()).get(i);
			assertThat(item.get("label").asText()).isNotBlank();
			assertThat(item.get("explicit").asBoolean()).isFalse();
			int effective = item.get("effectiveSensitivity").asInt();
			int def = conceptRepository.findByCode(code).orElseThrow().getDefaultSensitivity();
			assertThat(effective).isEqualTo(def);
		}
		assertThat(actualCodes).isSorted();
		assertThat(actualCodes).containsExactlyElementsOf(expectedCodes);
	}

	@Test
	void putPersistsExplicitPreferenceReflectedByGet() throws Exception {
		String token = registerAndLogin("explicit@example.com");
		User user = userFor("explicit@example.com");

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":0,"COOKIES":5}}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(conceptRepository.findAll().size()));

		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(user.getId(), "LOCATION"))
				.isPresent();
		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(user.getId(), "LOCATION")
				.orElseThrow().getSensitivity()).isZero();

		MvcResult result = mockMvc.perform(get("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(locationItem(result, "LOCATION").get("effectiveSensitivity").asInt()).isZero();
		assertThat(locationItem(result, "LOCATION").get("explicit").asBoolean()).isTrue();
		assertThat(locationItem(result, "COOKIES").get("effectiveSensitivity").asInt()).isEqualTo(5);
	}

	@Test
	void putValidationRejectsBadInputWithoutPersisting() throws Exception {
		String token = registerAndLogin("validation@example.com");
		User user = userFor("validation@example.com");

		String[] rejected = {
				"{\"preferences\":{\"LOCATION\":-1}}",
				"{\"preferences\":{\"LOCATION\":6}}",
				"{\"preferences\":{\"LOCATION\":null}}",
				"{\"preferences\":{\"NOPE\":3}}",
				"{\"preferences\":{\"LOCATION\":\"high\"}}",
				"{}"
		};
		for (String body : rejected) {
			mockMvc.perform(put("/api/v1/me/privacy-preferences")
							.header("Authorization", "Bearer " + token)
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
					.andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}

		assertThat(preferenceRepository.findByUser_IdOrderByConcept_CodeAsc(user.getId())).isEmpty();
	}

	@Test
	void usersAreIsolated() throws Exception {
		String tokenA = registerAndLogin("iso-pref-a@example.com");
		String tokenB = registerAndLogin("iso-pref-b@example.com");
		User userA = userFor("iso-pref-a@example.com");
		User userB = userFor("iso-pref-b@example.com");

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + tokenA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":1}}
								"""))
				.andExpect(status().isOk());

		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(userA.getId(), "LOCATION"))
				.isPresent();
		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(userB.getId(), "LOCATION"))
				.isEmpty();

		MvcResult result = mockMvc.perform(get("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(locationItem(result, "LOCATION").get("explicit").asBoolean()).isFalse();
	}

	@Test
	void clientSuppliedIdentityCannotOverridePrincipal() throws Exception {
		String tokenA = registerAndLogin("override-pref-a@example.com");
		registerAndLogin("override-pref-b@example.com");
		User userA = userFor("override-pref-a@example.com");
		User userB = userFor("override-pref-b@example.com");

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.queryParam("userId", userB.getId().toString())
						.header("X-User-Id", userB.getId().toString())
						.header("Authorization", "Bearer " + tokenA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"preferences":{"LOCATION":4},"userId":"%s"}
								""".formatted(userB.getId())))
				.andExpect(status().isOk());

		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(userA.getId(), "LOCATION")
				.orElseThrow().getSensitivity()).isEqualTo(4);
		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(userB.getId(), "LOCATION"))
				.isEmpty();
	}

	@Test
	void repeatedPutIsIdempotentAndUpdatesWithoutDuplicating() throws Exception {
		String token = registerAndLogin("idempotent-pref@example.com");
		User user = userFor("idempotent-pref@example.com");

		String body = "{\"preferences\":{\"LOCATION\":2}}";
		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());
		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		assertThat(preferenceRepository.findByUser_IdOrderByConcept_CodeAsc(user.getId())).hasSize(1);

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"preferences\":{\"LOCATION\":5}}"))
				.andExpect(status().isOk());

		assertThat(preferenceRepository.findByUser_IdOrderByConcept_CodeAsc(user.getId())).hasSize(1);
		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(user.getId(), "LOCATION")
				.orElseThrow().getSensitivity()).isEqualTo(5);
	}

	@Test
	void absentConceptsAreLeftUntouched() throws Exception {
		String token = registerAndLogin("merge@example.com");
		User user = userFor("merge@example.com");

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"preferences\":{\"LOCATION\":1}}"))
				.andExpect(status().isOk());
		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"preferences\":{\"COOKIES\":5}}"))
				.andExpect(status().isOk());

		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(user.getId(), "LOCATION")
				.orElseThrow().getSensitivity()).isEqualTo(1);
		assertThat(preferenceRepository.findByUser_IdAndConcept_Code(user.getId(), "COOKIES")
				.orElseThrow().getSensitivity()).isEqualTo(5);
	}

	@Test
	void persistedPreferenceFlowsIntoPersonalizedImpact() throws Exception {
		String tokenA = registerAndLogin("impact-a@example.com");
		registerAndLogin("impact-b@example.com");
		User userA = userFor("impact-a@example.com");
		User userB = userFor("impact-b@example.com");

		mockMvc.perform(put("/api/v1/me/privacy-preferences")
						.header("Authorization", "Bearer " + tokenA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"preferences\":{\"LOCATION\":0}}"))
				.andExpect(status().isOk());

		PolicyVersion v2 = craftTransition();
		ImpactAssessment assessmentA = impactAssessmentService.getOrCreateAssessment(
				userA.getId(), v2.getId());
		ImpactAssessment assessmentB = impactAssessmentService.getOrCreateAssessment(
				userB.getId(), v2.getId());

		assertThat(assessmentA.getAggregateScore()).isZero();
		assertThat(assessmentB.getAggregateScore()).isEqualTo(100);
		List<ImpactAssessmentBreakdown> breakdownsA = impactAssessmentService
				.listBreakdowns(userA.getId(), v2.getId());
		List<ImpactAssessmentBreakdown> breakdownsB = impactAssessmentService
				.listBreakdowns(userB.getId(), v2.getId());
		assertThat(breakdownsA).hasSize(1);
		assertThat(breakdownsA.get(0).getPersonalizedNormalized()).isZero();
		assertThat(breakdownsB).hasSize(1);
		assertThat(breakdownsB.get(0).getPersonalizedNormalized()).isEqualTo(100);
	}

	private PolicyVersion craftTransition() {
		Policy policy = policyRepository.saveAndFlush(new Policy(
				"P " + UUID.randomUUID(), "https://example.com/" + UUID.randomUUID()));
		String oldText = "old-" + UUID.randomUUID();
		String newText = "new-" + UUID.randomUUID() + " location sharing";
		PolicyVersion v1 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 1, oldText, "hash-old-" + UUID.randomUUID()));
		PolicyVersion v2 = versionRepository.saveAndFlush(
				new PolicyVersion(policy, 2, newText, "hash-new-" + UUID.randomUUID()));
		PolicyChangeRecord change = changeRepository.saveAndFlush(new PolicyChangeRecord(
				v1, v2, PolicyChangeType.MODIFIED, oldText, newText, 0));
		PrivacyConcept concept = conceptRepository.findByCode("LOCATION").orElseThrow();
		ChangeConceptMatch match = matchRepository.saveAndFlush(new ChangeConceptMatch(
				change, concept, "location sharing", "LOCATION:seed", "KEYWORD"));
		impactRepository.saveAndFlush(new ChangeImpact(match, "LOCATION", "MODIFIED", 10,
				new BigDecimal("1.00"), new BigDecimal("10.00"), 100, ImpactBand.CRITICAL, 1));
		return v2;
	}

	private tools.jackson.databind.JsonNode locationItem(MvcResult result, String code) throws Exception {
		var tree = objectMapper.readTree(result.getResponse().getContentAsString());
		for (var item : tree) {
			if (code.equals(item.get("conceptCode").asText())) {
				return item;
			}
		}
		throw new IllegalStateException("Concept " + code + " not in response");
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
