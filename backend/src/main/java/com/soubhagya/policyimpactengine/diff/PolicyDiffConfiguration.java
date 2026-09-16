package com.soubhagya.policyimpactengine.diff;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deterministic diff engine and SimHash utility as Spring beans.
 *
 * <p>The implementations themselves stay plain Java (no Spring stereotypes,
 * no behavior change); this configuration only exposes them for injection —
 * notably into the observation orchestrator — so the application context
 * can construct the version-to-version integration and the similarity signal.
 */
@Configuration
public class PolicyDiffConfiguration {

	@Bean
	public PolicyDiffEngine policyDiffEngine() {
		return new LineBasedPolicyDiffEngine();
	}

	@Bean
	public PolicySimHash policySimHash() {
		return new DefaultPolicySimHash();
	}
}
