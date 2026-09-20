package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreference;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;

/**
 * Phase 2P — Testcontainers repo tests for User and preferences.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserPrivacyPreferenceRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired private UserRepository userRepository;
	@Autowired private PrivacyConceptRepository conceptRepository;
	@Autowired private UserPrivacyPreferenceRepository prefRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		prefRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void v6MigrationApplies() {
		Integer v = jdbcTemplate.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='6' AND success=true", Integer.class);
		assertThat(v).isEqualTo(1);
	}

	@Test
	void userPersists() {
		User u = userRepository.saveAndFlush(new User());
		assertThat(u.getId()).isNotNull();
		assertThat(u.getCreatedAt()).isNotNull();
		assertThat(u.getUpdatedAt()).isNotNull();
	}

	@Test
	void preferencePersistsWithFKs() {
		User u = userRepository.saveAndFlush(new User());
		var concept = conceptRepository.findByCode("LOCATION").orElseThrow();
		UserPrivacyPreference pref = prefRepository.saveAndFlush(new UserPrivacyPreference(u, concept, 3));
		assertThat(pref.getId()).isNotNull();
		UserPrivacyPreference found = prefRepository.findById(pref.getId()).orElseThrow();
		assertThat(found.getSensitivity()).isEqualTo(3);
		assertThat(found.getUser().getId()).isEqualTo(u.getId());
		assertThat(found.getConcept().getCode()).isEqualTo("LOCATION");
	}

	@Test
	void uniqueUserConceptEnforced() {
		User u = userRepository.saveAndFlush(new User());
		var concept = conceptRepository.findByCode("LOCATION").orElseThrow();
		prefRepository.saveAndFlush(new UserPrivacyPreference(u, concept, 3));
		assertThatThrownBy(() -> prefRepository.saveAndFlush(new UserPrivacyPreference(u, concept, 5)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void updateSensitivityWorks() {
		User u = userRepository.saveAndFlush(new User());
		var concept = conceptRepository.findByCode("COOKIES").orElseThrow();
		UserPrivacyPreference pref = prefRepository.saveAndFlush(new UserPrivacyPreference(u, concept, 2));
		pref.updateSensitivity(5);
		prefRepository.saveAndFlush(pref);
		assertThat(prefRepository.findById(pref.getId()).orElseThrow().getSensitivity()).isEqualTo(5);
	}

	@Test
	void deletePreferenceWorks() {
		User u = userRepository.saveAndFlush(new User());
		var concept = conceptRepository.findByCode("LOCATION").orElseThrow();
		UserPrivacyPreference pref = prefRepository.saveAndFlush(new UserPrivacyPreference(u, concept, 4));
		prefRepository.delete(pref);
		prefRepository.flush();
		assertThat(prefRepository.findById(pref.getId())).isEmpty();
	}

	@Test
	void userScopedQueryWorks() {
		User a = userRepository.saveAndFlush(new User());
		User b = userRepository.saveAndFlush(new User());
		var loc = conceptRepository.findByCode("LOCATION").orElseThrow();
		var cookies = conceptRepository.findByCode("COOKIES").orElseThrow();
		prefRepository.saveAndFlush(new UserPrivacyPreference(a, loc, 5));
		prefRepository.saveAndFlush(new UserPrivacyPreference(b, cookies, 0));
		List<UserPrivacyPreference> forA = prefRepository.findByUser_IdOrderByConcept_CodeAsc(a.getId());
		List<UserPrivacyPreference> forB = prefRepository.findByUser_IdOrderByConcept_CodeAsc(b.getId());
		assertThat(forA).hasSize(1).extracting(p -> p.getConcept().getCode()).containsExactly("LOCATION");
		assertThat(forB).hasSize(1).extracting(p -> p.getConcept().getCode()).containsExactly("COOKIES");
	}

	@Test
	void timestampsBehavior() {
		User u = userRepository.saveAndFlush(new User());
		assertThat(u.getCreatedAt()).isNotNull();
		assertThat(u.getUpdatedAt()).isNotNull();
		var concept = conceptRepository.findByCode("LOCATION").orElseThrow();
		UserPrivacyPreference pref = prefRepository.saveAndFlush(new UserPrivacyPreference(u, concept, 1));
		assertThat(pref.getCreatedAt()).isNotNull();
		assertThat(pref.getUpdatedAt()).isNotNull();
	}

	@Test
	void immutabilityAppUser() {
		List<String> setters = Arrays.stream(User.class.getMethods()).map(Method::getName).filter(n -> n.startsWith("set")).toList();
		assertThat(setters).isEmpty();
		Arrays.stream(User.class.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Column.class))
				.forEach(f -> {
					if (f.getName().equals("updatedAt")) return;
					// Phase 8A: passwordHash is the single sanctioned mutable
					// credential (dedicated change flow in a later slice; no
					// public setter). Email stays immutable (updatable=false).
					if (f.getName().equals("passwordHash")) return;
					assertThat(f.getAnnotation(Column.class).updatable()).as("Column %s", f.getName()).isFalse();
				});
	}
}
