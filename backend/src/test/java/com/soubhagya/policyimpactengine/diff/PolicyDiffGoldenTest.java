package com.soubhagya.policyimpactengine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Realistic privacy-policy golden test for {@link LineBasedPolicyDiffEngine}.
 *
 * <p>Pins the expected additions/removals/modifications — with exact
 * old/new text — for a representative Version 1 → Version 2 policy update.
 * No impact analysis is performed here; this test covers textual
 * difference only.
 */
class PolicyDiffGoldenTest {

	private final PolicyDiffEngine engine = new LineBasedPolicyDiffEngine();

	private static final String VERSION_1 = """
			Personal Data

			We collect your location data.

			We retain account data for 30 days.

			You may opt out of targeted advertising.""";

	private static final String VERSION_2 = """
			Personal Data

			We collect your precise location data.

			We retain account data for 90 days.

			We may share location data with advertising partners.""";

	@Test
	void identicalGoldenVersionsProduceNoChanges() {
		assertThat(engine.diff(VERSION_1, VERSION_1).changes()).isEmpty();
		assertThat(engine.diff(VERSION_2, VERSION_2).changes()).isEmpty();
	}

	@Test
	void goldenVersionUpdateProducesExpectedChanges() {
		PolicyDiffResult result = engine.diff(VERSION_1, VERSION_2);

		assertThat(result.changes()).containsExactly(
				new PolicyChange(PolicyChangeType.MODIFIED,
						"We collect your location data.",
						"We collect your precise location data."),
				new PolicyChange(PolicyChangeType.MODIFIED,
						"We retain account data for 30 days.",
						"We retain account data for 90 days."),
				new PolicyChange(PolicyChangeType.MODIFIED,
						"You may opt out of targeted advertising.",
						"We may share location data with advertising partners."));
	}

	@Test
	void goldenDiffIsDeterministic() {
		PolicyDiffResult first = engine.diff(VERSION_1, VERSION_2);
		PolicyDiffResult second = engine.diff(VERSION_1, VERSION_2);

		assertThat(second).isEqualTo(first);
	}
}
