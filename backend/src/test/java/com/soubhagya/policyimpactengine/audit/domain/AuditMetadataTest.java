package com.soubhagya.policyimpactengine.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Phase 11A — pure unit tests for {@link AuditMetadata}: scalar
 * allowlisting, insertion-order preservation, and compact JSON form.
 * No Spring context, no database.
 */
class AuditMetadataTest {

	@Test
	void emptySerializesToEmptyObject() {
		AuditMetadata metadata = AuditMetadata.empty();

		assertThat(metadata.isEmpty()).isTrue();
		assertThat(metadata.toJson()).isEqualTo("{}");
	}

	@Test
	void insertionOrderAndCompactFormAreExact() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("conceptCode", "LOCATION");
		fields.put("oldSensitivity", 3);
		fields.put("newSensitivity", 5);
		fields.put("explicit", true);
		fields.put("cleared", null);

		assertThat(AuditMetadata.of(fields).toJson()).isEqualTo(
				"{\"conceptCode\":\"LOCATION\",\"oldSensitivity\":3,"
						+ "\"newSensitivity\":5,\"explicit\":true,\"cleared\":null}");
	}

	@Test
	void acceptedScalarTypes() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("s", "v");
		fields.put("i", 1);
		fields.put("l", 2L);
		fields.put("b", true);
		fields.put("n", null);
		fields.put("big", new java.math.BigInteger("42"));

		assertThat(AuditMetadata.of(fields).toJson()).isEqualTo(
				"{\"s\":\"v\",\"i\":1,\"l\":2,\"b\":true,\"n\":null,\"big\":42}");
	}

	@Test
	void floatsDecimalsNestingAndCollectionsAreRejected() {
		assertThatThrownBy(() -> AuditMetadata.of(Map.of("n", 1.5)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuditMetadata.of(Map.of("n", 1.5f)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuditMetadata.of(Map.of("n", new BigDecimal("1.5"))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuditMetadata.of(Map.of("n", Map.of("a", 1))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuditMetadata.of(Map.of("n", List.of(1))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuditMetadata.of(Map.of("n", new int[] { 1 })))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nullMapAndBlankKeysAreRejected() {
		assertThatThrownBy(() -> AuditMetadata.of(null))
				.isInstanceOf(IllegalArgumentException.class);
		Map<String, Object> blank = new LinkedHashMap<>();
		blank.put("  ", 1);
		assertThatThrownBy(() -> AuditMetadata.of(blank))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
