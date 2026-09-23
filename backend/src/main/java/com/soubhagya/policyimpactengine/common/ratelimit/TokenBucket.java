package com.soubhagya.policyimpactengine.common.ratelimit;

import java.time.Duration;
import java.time.Instant;

/**
 * Phase 13-C — a single token-bucket limiter (see DECISIONS.md
 * ADR-025).
 *
 * <p>Pure Spring-free logic: capacity tokens, pro-rata refill from the
 * caller-supplied instant (the application {@code Clock} in
 * production, fixed/sequenced clocks in tests). All state transitions
 * are synchronized per bucket, so concurrent consumes for the same key
 * count exactly. Negative clock steps refill nothing rather than
 * granting extra tokens.
 */
public final class TokenBucket {

	private final double capacity;
	private final double refillPerSecond;
	private double tokens;
	private Instant lastRefill;
	private Instant lastAccess;

	/** One consume verdict: allowed, plus whole seconds until the next token. */
	public record Verdict(boolean allowed, long retryAfterSeconds) {
	}

	public TokenBucket(int maxRequests, Duration window, Instant now) {
		if (maxRequests < 1) {
			throw new IllegalArgumentException("maxRequests must be >= 1");
		}
		if (window == null || window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException("window must be positive");
		}
		if (now == null) {
			throw new IllegalArgumentException("now must not be null");
		}
		this.capacity = maxRequests;
		this.refillPerSecond = (double) maxRequests / ((double) window.toMillis() / 1000.0);
		this.tokens = maxRequests;
		this.lastRefill = now;
		this.lastAccess = now;
	}

	/**
	 * Consumes one token when available. Rejections carry the whole
	 * seconds until one token refills (minimum 1); allowances carry 0.
	 */
	public synchronized Verdict tryConsume(Instant now) {
		if (now == null) {
			throw new IllegalArgumentException("now must not be null");
		}
		refill(now);
		lastAccess = now;
		if (tokens >= 1.0) {
			tokens -= 1.0;
			return new Verdict(true, 0);
		}
		long retryAfter = (long) Math.ceil((1.0 - tokens) / refillPerSecond);
		return new Verdict(false, Math.max(1, retryAfter));
	}

	/** True when no decision has touched this bucket within {@code ttl}. */
	public synchronized boolean isIdleSince(Instant now, Duration ttl) {
		if (now == null || ttl == null) {
			throw new IllegalArgumentException("arguments must not be null");
		}
		return Duration.between(lastAccess, now).compareTo(ttl) > 0;
	}

	/** Last decision instant, for oldest-first eviction. */
	public synchronized Instant lastAccess() {
		return lastAccess;
	}

	private void refill(Instant now) {
		long elapsedMillis = Duration.between(lastRefill, now).toMillis();
		if (elapsedMillis > 0) {
			tokens = Math.min(capacity,
					tokens + (elapsedMillis / 1000.0) * refillPerSecond);
			lastRefill = now;
		}
		else if (elapsedMillis < 0) {
			lastRefill = now;
		}
	}
}
