package com.soubhagya.policyimpactengine.audit.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 11A — immutable, insertion-ordered audit metadata (see
 * DECISIONS.md ADR-023 §6).
 *
 * <p>Holds only scalar values ({@link String}, integer numbers,
 * {@link Boolean}, or {@code null}); floats, nested maps,
 * collections, and arrays are rejected so serialization stays
 * deterministic. Serialized as compact JSON with keys in insertion
 * order — the caller-defined per-event field order — using the
 * project's Jackson mapper with default settings and no pretty
 * printing. The repository has no other canonical-JSON convention;
 * this is the smallest explicit deterministic choice.
 */
public final class AuditMetadata {

	private static final ObjectMapper WRITER = new ObjectMapper();

	private final Map<String, Object> fields;

	private AuditMetadata(Map<String, Object> fields) {
		this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}

	/**
	 * Returns metadata with no fields, serializing to {@code {}}.
	 * Distinct from absent (null) metadata, which canonicalizes to
	 * the empty string.
	 */
	public static AuditMetadata empty() {
		return new AuditMetadata(Map.of());
	}

	/**
	 * Copies the given fields preserving iteration order. Callers pass
	 * an insertion-ordered map whose order is the event's documented
	 * field order.
	 */
	public static AuditMetadata of(Map<String, ?> fields) {
		if (fields == null) {
			throw new IllegalArgumentException("Fields must not be null; use empty() or null metadata");
		}
		for (Map.Entry<String, ?> entry : fields.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) {
				throw new IllegalArgumentException("Metadata key must not be blank");
			}
			assertScalar(entry.getKey(), entry.getValue());
		}
		return new AuditMetadata(new LinkedHashMap<>(fields));
	}

	/**
	 * Serializes to compact JSON with keys in insertion order.
	 * Cannot fail for the scalar values this class admits.
	 */
	public String toJson() {
		try {
			return WRITER.writeValueAsString(fields);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Metadata serialization failed", ex);
		}
	}

	public boolean isEmpty() {
		return fields.isEmpty();
	}

	private static void assertScalar(String key, Object value) {
		if (value == null || value instanceof String || value instanceof Boolean
				|| value instanceof Integer || value instanceof Long
				|| value instanceof Short || value instanceof Byte
				|| value instanceof java.math.BigInteger) {
			return;
		}
		throw new IllegalArgumentException(
				"Metadata value for '" + key + "' must be a string, integer, boolean, or null");
	}
}
