package com.soubhagya.policyimpactengine.intelligence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deterministic concept matcher as a Spring bean.
 *
 * <p>The implementation stays plain Java (no Spring stereotype, no DB/I/O);
 * this configuration only exposes it for injection into the observation
 * persistence service.
 */
@Configuration
public class IntelligenceConfiguration {

	@Bean
	public ConceptMatcher conceptMatcher() {
		return new DeterministicConceptMatcher();
	}
}
