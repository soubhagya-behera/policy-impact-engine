package com.soubhagya.policyimpactengine.user;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase 2P — wires deterministic resolver as bean.
 *
 * <p>Phase 8A adds the BCrypt {@link PasswordEncoder}. No custom crypto.
 * Phase 8B enables {@code security.jwt} binding for {@link JwtService}.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
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
