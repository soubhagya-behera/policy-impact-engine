package com.soubhagya.policyimpactengine.common.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 13-C — binds {@link RateLimitProperties} from
 * {@code rate-limit.*} (see DECISIONS.md ADR-025).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {
}
