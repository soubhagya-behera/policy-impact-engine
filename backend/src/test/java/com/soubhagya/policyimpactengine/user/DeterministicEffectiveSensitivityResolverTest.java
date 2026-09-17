package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreference;

/**
 * Phase 2P — pure unit tests for resolver (no Spring/DB).
 */
class DeterministicEffectiveSensitivityResolverTest {

	private final EffectiveSensitivityResolver resolver = new DeterministicEffectiveSensitivityResolver();

	private PrivacyConcept concept(String code, int defaultSens) {
		return new PrivacyConcept(code, code + " label", "desc " + code, 5, defaultSens);
	}

	private User user() {
		User u = new User();
		try {
			var f = User.class.getDeclaredField("id");
			f.setAccessible(true);
			f.set(u, UUID.randomUUID());
		} catch (Exception e) { throw new RuntimeException(e); }
		return u;
	}

	@Test
	void noPreferenceFallsBackToDefault() {
		PrivacyConcept loc = concept("LOCATION", 3);
		Map<String, Integer> map = resolver.resolveAll(List.of(), List.of(loc));
		assertThat(map).containsEntry("LOCATION", 3);
	}

	@Test
	void explicitZeroOverridesDefault() {
		PrivacyConcept loc = concept("LOCATION", 3);
		User u = user();
		UserPrivacyPreference pref = new UserPrivacyPreference(u, loc, 0);
		Map<String, Integer> map = resolver.resolveAll(List.of(pref), List.of(loc));
		assertThat(map).containsEntry("LOCATION", 0);
		assertThat(resolver.resolve(pref, loc)).isZero();
	}

	@Test
	void explicitOne() {
		PrivacyConcept c = concept("COOKIES", 2);
		User u = user();
		assertThat(resolver.resolveAll(List.of(new UserPrivacyPreference(u, c, 1)), List.of(c))).containsEntry("COOKIES", 1);
	}

	@Test
	void explicitThree() {
		PrivacyConcept c = concept("ADVERTISING", 3);
		User u = user();
		assertThat(resolver.resolveAll(List.of(new UserPrivacyPreference(u, c, 3)), List.of(c))).containsEntry("ADVERTISING", 3);
	}

	@Test
	void explicitFive() {
		PrivacyConcept c = concept("THIRD_PARTY_SHARING", 4);
		User u = user();
		assertThat(resolver.resolveAll(List.of(new UserPrivacyPreference(u, c, 5)), List.of(c))).containsEntry("THIRD_PARTY_SHARING", 5);
	}

	@Test
	void multipleConcepts() {
		PrivacyConcept loc = concept("LOCATION", 3);
		PrivacyConcept cookies = concept("COOKIES", 2);
		PrivacyConcept adv = concept("ADVERTISING", 3);
		User u = user();
		var prefs = List.of(new UserPrivacyPreference(u, loc, 5), new UserPrivacyPreference(u, cookies, 0));
		Map<String, Integer> map = resolver.resolveAll(prefs, List.of(loc, cookies, adv));
		assertThat(map).containsEntry("LOCATION", 5).containsEntry("COOKIES", 0).containsEntry("ADVERTISING", 3);
	}

	@Test
	void deterministicOrderingByCode() {
		PrivacyConcept b = concept("B_CODE", 2);
		PrivacyConcept a = concept("A_CODE", 3);
		Map<String, Integer> map = resolver.resolveAll(List.of(), List.of(b, a));
		assertThat(map.keySet()).containsExactly("A_CODE", "B_CODE");
	}

	@Test
	void repeatedExecutionIdentical() {
		PrivacyConcept loc = concept("LOCATION", 3);
		User u = user();
		var prefs = List.of(new UserPrivacyPreference(u, loc, 5));
		assertThat(resolver.resolveAll(prefs, List.of(loc))).isEqualTo(resolver.resolveAll(prefs, List.of(loc)));
	}

	@Test
	void unknownConceptNotInResultUnlessInConceptsList() {
		PrivacyConcept loc = concept("LOCATION", 3);
		PrivacyConcept adv = concept("ADVERTISING", 3);
		User u = user();
		Map<String, Integer> map = resolver.resolveAll(List.of(new UserPrivacyPreference(u, loc, 5)), List.of(loc));
		assertThat(map).doesNotContainKey("ADVERTISING");
	}

	@Test
	void invalidSensitivityThrows() {
		PrivacyConcept loc = concept("LOCATION", 3);
		User u = user();
		assertThatThrownBy(() -> new UserPrivacyPreference(u, loc, -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserPrivacyPreference(u, loc, 6)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void explicitOverridesDefault() {
		PrivacyConcept loc = concept("LOCATION", 4);
		User u = user();
		UserPrivacyPreference pref = new UserPrivacyPreference(u, loc, 2);
		assertThat(resolver.resolve(pref, loc)).isEqualTo(2);
		assertThat(resolver.resolve(null, loc)).isEqualTo(4);
	}

	@Test
	void differentUsersIndependentMaps() {
		PrivacyConcept loc = concept("LOCATION", 3);
		User uA = user(); User uB = user();
		Map<String, Integer> mapA = resolver.resolveAll(List.of(new UserPrivacyPreference(uA, loc, 5)), List.of(loc));
		Map<String, Integer> mapB = resolver.resolveAll(List.of(new UserPrivacyPreference(uB, loc, 0)), List.of(loc));
		assertThat(mapA).containsEntry("LOCATION", 5);
		assertThat(mapB).containsEntry("LOCATION", 0);
	}

	@Test
	void nullInputsThrow() {
		PrivacyConcept loc = concept("LOCATION", 3);
		assertThatThrownBy(() -> resolver.resolveAll(null, List.of(loc))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resolver.resolveAll(List.of(), null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resolver.resolve(null, null)).isInstanceOf(IllegalArgumentException.class);
	}
}
