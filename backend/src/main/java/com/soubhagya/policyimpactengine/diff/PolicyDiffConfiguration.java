package com.soubhagya.policyimpactengine.diff;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deterministic diff engine as a Spring bean.
 *
 * <p>The implementation itself stays plain Java (no Spring stereotypes,
 * no behavior change); this configuration only exposes it for injection —
 * notably into the observation orchestrator — so the application context
 * can construct the version-to-version integration.
 */
@Configuration
public class PolicyDiffConfiguration {

	@Bean
	public PolicyDiffEngine policyDiffEngine() {
		return new LineBasedPolicyDiffEngine();
	}
}
