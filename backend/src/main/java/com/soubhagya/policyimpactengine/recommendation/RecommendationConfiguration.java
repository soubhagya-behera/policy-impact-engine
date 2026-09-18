package com.soubhagya.policyimpactengine.recommendation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 2R — wires the deterministic recommendation engine as Spring bean.
 */
@Configuration
public class RecommendationConfiguration {

	@Bean
	public RecommendationEngine recommendationEngine() {
		return new DeterministicRecommendationEngine();
	}
}
