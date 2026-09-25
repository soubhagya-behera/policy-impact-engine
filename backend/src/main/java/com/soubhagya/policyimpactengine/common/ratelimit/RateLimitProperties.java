package com.soubhagya.policyimpactengine.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 13-C — rate-limit configuration bound from {@code rate-limit}
 * (see DECISIONS.md ADR-025).
 *
 * <p>Three request tiers plus an anonymous tier, each a token bucket
 * whose capacity equals its window maximum. {@code enabled} is the
 * incident kill-switch: when false the filter passes everything
 * through. Non-positive values fail fast here at binding time so a
 * misconfigured deployment never boots half-limited.
 *
 * <p>Phase 14-A/1 reserves the refresh tier (see DECISIONS.md
 * ADR-029): same shape and validation as the auth tier, consumed by
 * filter routing in a later slice — no service or filter behavior
 * attaches to it here.
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(

		@DefaultValue("true") boolean enabled,
		@DefaultValue("PT1M") Duration authWindow,
		@DefaultValue("10") int authMaxRequests,
		@DefaultValue("PT1M") Duration apiWindow,
		@DefaultValue("600") int apiMaxRequests,
		@DefaultValue("PT1M") Duration explanationWindow,
		@DefaultValue("10") int explanationMaxRequests,
		@DefaultValue("PT1M") Duration anonymousWindow,
		@DefaultValue("60") int anonymousMaxRequests,
		@DefaultValue("100000") int maxTrackedKeys,
		@DefaultValue("PT10M") Duration idleTtl,
		@DefaultValue("PT1M") Duration refreshWindow,
		@DefaultValue("10") int refreshMaxRequests

) {

	public RateLimitProperties {
		if (authWindow == null || authWindow.isZero() || authWindow.isNegative()) {
			throw new IllegalArgumentException("rate-limit.auth-window must be positive");
		}
		if (authMaxRequests < 1) {
			throw new IllegalArgumentException("rate-limit.auth-max-requests must be >= 1");
		}
		if (apiWindow == null || apiWindow.isZero() || apiWindow.isNegative()) {
			throw new IllegalArgumentException("rate-limit.api-window must be positive");
		}
		if (apiMaxRequests < 1) {
			throw new IllegalArgumentException("rate-limit.api-max-requests must be >= 1");
		}
		if (explanationWindow == null || explanationWindow.isZero()
				|| explanationWindow.isNegative()) {
			throw new IllegalArgumentException(
					"rate-limit.explanation-window must be positive");
		}
		if (explanationMaxRequests < 1) {
			throw new IllegalArgumentException(
					"rate-limit.explanation-max-requests must be >= 1");
		}
		if (anonymousWindow == null || anonymousWindow.isZero()
				|| anonymousWindow.isNegative()) {
			throw new IllegalArgumentException("rate-limit.anonymous-window must be positive");
		}
		if (anonymousMaxRequests < 1) {
			throw new IllegalArgumentException(
					"rate-limit.anonymous-max-requests must be >= 1");
		}
		if (maxTrackedKeys < 1) {
			throw new IllegalArgumentException("rate-limit.max-tracked-keys must be >= 1");
		}
		if (idleTtl == null || idleTtl.isZero() || idleTtl.isNegative()) {
			throw new IllegalArgumentException("rate-limit.idle-ttl must be positive");
		}
		if (refreshWindow == null || refreshWindow.isZero() || refreshWindow.isNegative()) {
			throw new IllegalArgumentException("rate-limit.refresh-window must be positive");
		}
		if (refreshMaxRequests < 1) {
			throw new IllegalArgumentException("rate-limit.refresh-max-requests must be >= 1");
		}
	}
}
