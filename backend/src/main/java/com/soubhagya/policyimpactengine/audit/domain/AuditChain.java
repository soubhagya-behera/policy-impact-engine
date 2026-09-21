package com.soubhagya.policyimpactengine.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase 11A — pure SHA-256 audit chain support implementing the frozen
 * protocol in DECISIONS.md ADR-023 (canonical field order, NULL
 * handling, escaping, microsecond timestamps, UTF-8, lowercase hex).
 *
 * <p>Plain Java: no Spring, no database, no clock, no randomness.
 * Stateless and thread-safe. Callers truncate instants to
 * microseconds before persisting (PostgreSQL {@code timestamptz}
 * precision) — canonicalization truncates defensively as well, so
 * verify-after-persist recomputation always matches.
 */
public final class AuditChain {

	/** Canonical-format version rendered as decimal ASCII. */
	public static final int CANONICAL_FORMAT_VERSION = 1;

	private static final DateTimeFormatter OCCURRED_AT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private AuditChain() {
		// Pure functions; not instantiable.
	}

	/**
	 * Builds the exact canonical hash-input string: the eight textual
	 * fields in ADR-023 order, escaped, joined with single pipes.
	 * NULL UUID/resource/metadata/prev values render as empty strings;
	 * {@code occurredAt} and {@code eventType} are required.
	 */
	public static String canonicalInput(int version, String eventType, UUID actorUserId,
			String resourceType, UUID resourceId, Instant occurredAt, String metadataJson,
			String prevHash) {
		if (eventType == null) {
			throw new IllegalArgumentException("Event type must not be null");
		}
		if (occurredAt == null) {
			throw new IllegalArgumentException("Occurred at must not be null");
		}
		return version + "|"
				+ escape(eventType) + "|"
				+ escape(uuidText(actorUserId)) + "|"
				+ escape(nullToEmpty(resourceType)) + "|"
				+ escape(uuidText(resourceId)) + "|"
				+ escape(formatOccurredAt(occurredAt)) + "|"
				+ escape(nullToEmpty(metadataJson)) + "|"
				+ escape(nullToEmpty(prevHash));
	}

	/**
	 * Convenience composition: canonicalizes then hashes.
	 */
	public static String canonicalHash(int version, String eventType, UUID actorUserId,
			String resourceType, UUID resourceId, Instant occurredAt, String metadataJson,
			String prevHash) {
		return sha256Hex(canonicalInput(version, eventType, actorUserId, resourceType,
				resourceId, occurredAt, metadataJson, prevHash));
	}

	/**
	 * Renders an instant in fixed UTC microsecond form
	 * ({@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'}), truncating finer
	 * precision first so the output always carries six digits.
	 */
	public static String formatOccurredAt(Instant occurredAt) {
		if (occurredAt == null) {
			throw new IllegalArgumentException("Occurred at must not be null");
		}
		return OCCURRED_AT.format(occurredAt.truncatedTo(ChronoUnit.MICROS));
	}

	/**
	 * SHA-256 of the UTF-8 bytes, lowercase hexadecimal. Platform
	 * default charset is never used.
	 */
	public static String sha256Hex(String canonicalInput) {
		if (canonicalInput == null) {
			throw new IllegalArgumentException("Canonical input must not be null");
		}
		byte[] digest;
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			digest = messageDigest.digest(canonicalInput.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
		char[] out = new char[digest.length * 2];
		for (int i = 0; i < digest.length; i++) {
			int value = digest[i] & 0xFF;
			out[i * 2] = HEX[value >>> 4];
			out[i * 2 + 1] = HEX[value & 0x0F];
		}
		return new String(out);
	}

	private static String uuidText(UUID id) {
		return id == null ? "" : id.toString();
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("|", "\\|");
	}
}
