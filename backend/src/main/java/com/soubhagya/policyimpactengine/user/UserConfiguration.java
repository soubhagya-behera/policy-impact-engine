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
 * Phase 18-B enables {@code app.google} binding for
 * {@link com.soubhagya.policyimpactengine.user.web.GoogleOAuthProperties}
 * (see DECISIONS.md ADR-037).
 */
@Configuration
@EnableConfigurationProperties({ JwtProperties.class,
		com.soubhagya.policyimpactengine.user.web.GoogleOAuthProperties.class })
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
