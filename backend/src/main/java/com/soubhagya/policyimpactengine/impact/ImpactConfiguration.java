package com.soubhagya.policyimpactengine.impact;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 2O — wires deterministic scoring engine as Spring bean.
 */
@Configuration
public class ImpactConfiguration {

	@Bean
	public ImpactScoringEngine impactScoringEngine() {
		return new DeterministicImpactScoringEngine();
	}
}
