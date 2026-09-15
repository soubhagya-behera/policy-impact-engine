package com.soubhagya.policyimpactengine.policy.fetch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the stateless deterministic pipeline stages as Spring beans.
 *
 * <p>The implementations themselves stay plain Java (no Spring
 * stereotypes, no behavior change); this configuration only exposes
 * them for injection — notably into the observation orchestrator —
 * so the application context can construct the full pipeline.
 */
@Configuration
public class PolicyFetchConfiguration {

	@Bean
	public PolicyContentExtractor policyContentExtractor() {
		return new JsoupPolicyContentExtractor();
	}

	@Bean
	public PolicyTextNormalizer policyTextNormalizer() {
		return new DefaultPolicyTextNormalizer();
	}

	@Bean
	public PolicyContentHasher policyContentHasher() {
		return new Sha256PolicyContentHasher();
	}
}
