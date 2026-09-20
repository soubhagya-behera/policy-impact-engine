package com.soubhagya.policyimpactengine.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase 2P — wires deterministic resolver as bean.
 *
 * <p>Phase 8A adds the BCrypt {@link PasswordEncoder}. No custom crypto.
 */
@Configuration
public class UserConfiguration {

	@Bean
	public EffectiveSensitivityResolver effectiveSensitivityResolver() {
		return new DeterministicEffectiveSensitivityResolver();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
