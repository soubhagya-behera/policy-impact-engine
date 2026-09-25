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
 *
 * <p>Phase 14-A/1 adds {@code refreshTokenTtl} (see DECISIONS.md
 * ADR-029): null stays unset and resolves to the service default;
 * positivity is enforced fail-fast by {@link JwtService}.
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(

		String secret,
		Duration accessTokenTtl,
		Duration refreshTokenTtl

) {
}
