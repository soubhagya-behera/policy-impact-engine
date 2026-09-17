package com.soubhagya.policyimpactengine.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 2P — wires deterministic resolver as bean.
 */
@Configuration
public class UserConfiguration {

	@Bean
	public EffectiveSensitivityResolver effectiveSensitivityResolver() {
		return new DeterministicEffectiveSensitivityResolver();
	}
}
