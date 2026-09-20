package com.soubhagya.policyimpactengine.user;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 8B — JWT configuration bound from {@code security.jwt}.
 *
 * <p>The secret comes from environment / Git-ignored local configuration
 * only; the committed template carries a placeholder. Length and
 * presence are validated fail-fast by {@link JwtService}, not here, so
 * binding itself stays lenient.
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(

		String secret,
		Duration accessTokenTtl

) {
}
