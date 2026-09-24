package com.soubhagya.policyimpactengine.common.web.cors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Phase 13-D — deny-by-default CORS source (see DECISIONS.md
 * ADR-027).
 *
 * <p>With no configured origins the source answers every request
 * with {@code null}, so no CORS headers are ever emitted. With
 * origins configured, exact-origin mappings apply to
 * {@code /api/**} only — never to {@code /actuator/**}.
 * Credentials stay disabled and {@code X-Forwarded-For} is never
 * trusted (ADR-025 precedent).
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

	@Bean
	public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
		if (properties.allowedOrigins().isEmpty()) {
			return request -> null;
		}
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(properties.allowedOrigins());
		configuration.setAllowedMethods(properties.allowedMethods());
		configuration.setAllowedHeaders(properties.allowedHeaders());
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(properties.maxAge());
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}
}
