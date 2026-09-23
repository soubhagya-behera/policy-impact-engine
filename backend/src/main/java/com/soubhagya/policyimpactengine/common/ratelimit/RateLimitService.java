package com.soubhagya.policyimpactengine.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Phase 13-C — in-memory per-key token-bucket registry (see DECISIONS.md
 * ADR-025).
 *
 * <p>Keys are namespaced {@code tier:value} strings so IP and user
 * buckets can never collide. Same-key concurrency is exact through the
 * per-bucket monitor; the map itself is a {@code ConcurrentHashMap}
 * with no static state. Growth is bounded: idle entries past the
 * configured TTL are evicted lazily, and when tracked keys exceed
 * {@code max-tracked-keys} a purge pass drops idle entries first,
 * then oldest-accessed ones until back under cap. A restart resets all
 * buckets; the limit converges again within one window.
 */
@Service
public class RateLimitService {

	private final RateLimitProperties properties;
	private final Clock clock;
	private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

	/** One limiter decision: allowed, plus whole seconds to advertise. */
	public record Decision(boolean allowed, long retryAfterSeconds) {
	}

	public RateLimitService(RateLimitProperties properties, Clock clock) {
		if (properties == null || clock == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.properties = properties;
		this.clock = clock;
	}

	/** Registration attempt from one client IP (own bucket). */
	public Decision registerAttempt(String clientIp) {
		return decide("auth-register", requireText(clientIp, "clientIp"),
				properties.authWindow(), properties.authMaxRequests());
	}

	/** Login attempt from one client IP (own bucket). */
	public Decision loginAttempt(String clientIp) {
		return decide("auth-login", requireText(clientIp, "clientIp"),
				properties.authWindow(), properties.authMaxRequests());
	}

	/** General authenticated API request for one user. */
	public Decision apiRequest(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId must not be null");
		}
		return decide("api", userId.toString(),
				properties.apiWindow(), properties.apiMaxRequests());
	}

	/** Costly explanation request for one user. */
	public Decision explanationRequest(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId must not be null");
		}
		return decide("explanation", userId.toString(),
				properties.explanationWindow(), properties.explanationMaxRequests());
	}

	/** Unauthenticated API request from one client IP. */
	public Decision anonymousRequest(String clientIp) {
		return decide("anonymous", requireText(clientIp, "clientIp"),
				properties.anonymousWindow(), properties.anonymousMaxRequests());
	}

	/** Current tracked-key count, for tests and operations. */
	public int trackedKeys() {
		return buckets.size();
	}

	private Decision decide(String tier, String key, Duration window, int maxRequests) {
		Instant now = clock.instant();
		String namespaced = tier + ":" + key;
		TokenBucket bucket = buckets.get(namespaced);
		if (bucket != null && bucket.isIdleSince(now, properties.idleTtl())) {
			buckets.remove(namespaced, bucket);
			bucket = null;
		}
		if (bucket == null) {
			TokenBucket fresh = new TokenBucket(maxRequests, window, now);
			bucket = buckets.putIfAbsent(namespaced, fresh);
			if (bucket == null) {
				bucket = fresh;
			}
			else if (bucket.isIdleSince(now, properties.idleTtl())) {
				buckets.replace(namespaced, bucket, fresh);
				bucket = fresh;
			}
		}
		enforceBound(now);
		TokenBucket.Verdict verdict = bucket.tryConsume(now);
		return new Decision(verdict.allowed(), verdict.retryAfterSeconds());
	}

	private void enforceBound(Instant now) {
		if (buckets.size() <= properties.maxTrackedKeys()) {
			return;
		}
		buckets.entrySet().removeIf(entry ->
				entry.getValue().isIdleSince(now, properties.idleTtl()));
		if (buckets.size() <= properties.maxTrackedKeys()) {
			return;
		}
		List<Map.Entry<String, TokenBucket>> entries =
				new ArrayList<>(buckets.entrySet());
		entries.sort(Comparator.comparing(entry -> entry.getValue().lastAccess()));
		int excess = buckets.size() - properties.maxTrackedKeys();
		for (int i = 0; i < excess && i < entries.size(); i++) {
			buckets.remove(entries.get(i).getKey(), entries.get(i).getValue());
		}
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
