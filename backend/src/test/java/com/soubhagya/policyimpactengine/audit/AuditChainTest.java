package com.soubhagya.policyimpactengine.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.audit.domain.AuditChain;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;

/**
 * Phase 11A — pure unit tests for the frozen ADR-023 canonicalization
 * and SHA-256 support. No Spring context, no database: determinism is
 * pinned byte-for-byte, including the normative genesis golden vector.
 */
class AuditChainTest {

	private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID RESOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final String HEAD = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void genesisGoldenVectorMatchesFrozenAdrValue() {
		String canonical = AuditChain.canonicalInput(
				AuditChain.CANONICAL_FORMAT_VERSION,
				"AUTH_USER_REGISTERED",
				UUID.fromString("11111111-1111-1111-1111-111111111111"),
				"USER",
				UUID.fromString("11111111-1111-1111-1111-111111111111"),
				Instant.parse("2026-01-01T00:00:00Z"),
				"{}",
				null);

		assertThat(canonical).isEqualTo(
				"1|AUTH_USER_REGISTERED|11111111-1111-1111-1111-111111111111"
						+ "|USER|11111111-1111-1111-1111-111111111111"
						+ "|2026-01-01T00:00:00.000000Z|{}|");
		assertThat(AuditChain.sha256Hex(canonical)).isEqualTo(
				"6c3fd51f3d347c9d818b4cc35d6e2dafd83a11b849f62e1bee3c31f5fa3fcec4");
	}

	@Test
	void canonicalFieldOrderAndDelimitersAreExact() {
		String canonical = AuditChain.canonicalInput(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, Instant.parse("2026-09-21T07:30:00.5Z"),
				"{\"url\":\"https://example.com/p\"}", HEAD);

		assertThat(canonical).isEqualTo(
				"1|POLICY_REGISTERED|11111111-1111-1111-1111-111111111111"
						+ "|POLICY|22222222-2222-2222-2222-222222222222"
						+ "|2026-09-21T07:30:00.500000Z|{\"url\":\"https://example.com/p\"}|"
						+ HEAD);
	}

	@Test
	void nullFieldsRenderAsEmptySegments() {
		String canonical = AuditChain.canonicalInput(1, "AUTH_LOGIN_SUCCEEDED", null, null,
				null, Instant.parse("2026-01-02T00:00:00Z"), null, null);

		assertThat(canonical)
				.isEqualTo("1|AUTH_LOGIN_SUCCEEDED||||2026-01-02T00:00:00.000000Z||");
	}

	@Test
	void uuidTextIsNormalizedToLowercase() {
		UUID upper = UUID.fromString("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA");
		UUID lower = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		Instant at = Instant.parse("2026-01-01T00:00:00Z");

		assertThat(AuditChain.canonicalInput(1, "POLICY_REGISTERED", upper, "POLICY", lower,
				at, null, null))
				.isEqualTo(AuditChain.canonicalInput(1, "POLICY_REGISTERED", lower, "POLICY",
						lower, at, null, null))
				.contains("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	}

	@Test
	void timestampIsTruncatedToMicrosecondsWithFixedWidth() {
		assertThat(AuditChain.formatOccurredAt(
				Instant.parse("2026-03-04T05:06:07.123456789Z")))
				.isEqualTo("2026-03-04T05:06:07.123456Z");
		assertThat(AuditChain.formatOccurredAt(Instant.parse("2026-03-04T05:06:07Z")))
				.isEqualTo("2026-03-04T05:06:07.000000Z");
	}

	@Test
	void pipeAndBackslashAreEscapedDelimiterFirst() {
		String canonical = AuditChain.canonicalInput(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, Instant.parse("2026-01-01T00:00:00Z"), "{\"note\":\"a|b\\c\"}", null);

		assertThat(canonical).isEqualTo(
				"1|POLICY_REGISTERED|11111111-1111-1111-1111-111111111111"
						+ "|POLICY|22222222-2222-2222-2222-222222222222"
						+ "|2026-01-01T00:00:00.000000Z|{\"note\":\"a\\|b\\\\c\"}|");
	}

	@Test
	void metadataSerializationIsDeterministicAndCompact() {
		java.util.Map<String, Object> first = new java.util.LinkedHashMap<>();
		first.put("url", "https://example.com/p");
		first.put("n", 7);
		java.util.Map<String, Object> second = new java.util.LinkedHashMap<>();
		second.put("url", "https://example.com/p");
		second.put("n", 7);

		String one = AuditChain.canonicalInput(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, Instant.parse("2026-01-01T00:00:00Z"),
				AuditMetadata.of(first).toJson(),
				null);
		String two = AuditChain.canonicalInput(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, Instant.parse("2026-01-01T00:00:00Z"),
				AuditMetadata.of(second).toJson(),
				null);

		assertThat(one).isEqualTo(two);
		assertThat(one).contains("{\"url\":\"https://example.com/p\",\"n\":7}");
	}

	@Test
	void unicodeContentHashesDeterministicallyAsUtf8() {
		String metadata = "{\"label\":\"caf\u00e9 M\u00fcller\"}";
		Instant at = Instant.parse("2026-01-01T00:00:00Z");

		String one = AuditChain.canonicalHash(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, at, metadata, null);
		String two = AuditChain.canonicalHash(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, at, metadata, null);

		assertThat(one).isEqualTo(two).hasSize(64);
		assertThat(AuditChain.canonicalInput(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, at, metadata, null)).contains("caf\u00e9 M\u00fcller");
	}

	@Test
	void anyFieldChangeChangesTheHash() {
		Instant at = Instant.parse("2026-01-01T00:00:00Z");
		String base = AuditChain.canonicalHash(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, at, null, null);

		assertThat(AuditChain.canonicalHash(2, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, at, null, null)).isNotEqualTo(base);
		assertThat(AuditChain.canonicalHash(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, at.plusSeconds(1), null, null)).isNotEqualTo(base);
		assertThat(AuditChain.canonicalHash(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, at, "{}", null)).isNotEqualTo(base);
		assertThat(AuditChain.canonicalHash(1, "POLICY_REGISTERED", ACTOR, "POLICY",
				RESOURCE, at, null, HEAD)).isNotEqualTo(base);
	}

	@Test
	void nullInputsAreRejected() {
		assertThatThrownBy(() -> AuditChain.canonicalInput(1, null, ACTOR, "POLICY",
				RESOURCE, Instant.now(), null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuditChain.canonicalInput(1, "POLICY_REGISTERED", ACTOR,
				"POLICY", RESOURCE, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuditChain.sha256Hex(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuditChain.formatOccurredAt(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
