package com.soubhagya.policyimpactengine.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.soubhagya.policyimpactengine.audit.application.AuditVerificationService.ChainRow;
import com.soubhagya.policyimpactengine.audit.domain.AuditChain;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditVerificationResult;
import com.soubhagya.policyimpactengine.audit.domain.AuditVerificationResult.FailureReason;

/**
 * Phase 11B — pure unit tests for the deterministic verification
 * walk. No Spring context, no database: rows are built in memory
 * with hashes computed through the frozen {@link AuditChain}, then
 * verified or tampered field by field.
 */
class AuditVerificationServiceTest {

	private static final UUID ID1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID ID2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID ID3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final UUID ID4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
	private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID RESOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final Instant FIRST = Instant.parse("2026-09-18T10:00:00Z");
	private static final String HEAD = "0".repeat(64);
	private static final String DANGLING = "f".repeat(64);

	@Test
	void emptyChainIsValidWithZeroCount() {
		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of());

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isZero();
		assertThat(result.getFailedEventId()).isEmpty();
		assertThat(result.getFailedPosition()).isEmpty();
		assertThat(result.getFailureReason()).isEmpty();
	}

	@Test
	void adrGoldenVectorVerifies() {
		UUID vectorId = UUID.randomUUID();
		ChainRow genesis = validRow(vectorId, AuditEventType.AUTH_USER_REGISTERED,
				UUID.fromString("11111111-1111-1111-1111-111111111111"), "USER",
				UUID.fromString("11111111-1111-1111-1111-111111111111"),
				Instant.parse("2026-01-01T00:00:00Z"), "{}", null);

		assertThat(genesis.eventHash()).isEqualTo(
				"6c3fd51f3d347c9d818b4cc35d6e2dafd83a11b849f62e1bee3c31f5fa3fcec4");
		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of(genesis));

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(1);
	}

	@Test
	void validThreeEventChainVerifies() {
		AuditVerificationResult result = AuditVerificationService.verifyChain(threeChain());

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(3);
		assertThat(result.getFailureReason()).isEmpty();
	}

	@Test
	void genesisWithNullActorVerifies() {
		ChainRow genesis = validRow(ID1, AuditEventType.AUTH_LOGIN_SUCCEEDED, null,
				null, null, FIRST, null, null);

		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of(genesis));

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(1);
	}

	@Test
	void tamperedMetadataIsDetectedAtItsPosition() {
		List<ChainRow> rows = threeChain();
		ChainRow tampered = withMetadata(rows.get(1), "{\"conceptCode\":\"COOKIES\"}");

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(rows.get(0), tampered, rows.get(2)));

		assertInvalid(result, 1, ID2, 1, FailureReason.HASH_MISMATCH);
	}

	@Test
	void nullToEmptyObjectFlipIsDetected() {
		ChainRow genesis = validRow(ID1, AuditEventType.AUTH_USER_REGISTERED, ACTOR,
				"USER", ACTOR, FIRST, null, null);
		ChainRow flipped = withMetadata(genesis, "{}");

		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of(flipped));

		assertInvalid(result, 0, ID1, 0, FailureReason.HASH_MISMATCH);
	}

	@Test
	void tamperedEventHashIsDetected() {
		List<ChainRow> rows = threeChain();
		ChainRow tampered = withEventHash(rows.get(2), HEAD);

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(rows.get(0), rows.get(1), tampered));

		assertInvalid(result, 2, ID3, 2, FailureReason.HASH_MISMATCH);
	}

	@Test
	void prevHashRetargetedAtExistingHashCreatesFork() {
		List<ChainRow> rows = threeChain();
		ChainRow forked = withPrevHash(rows.get(2), rows.get(0).eventHash());
		ChainRow reforged = withEventHash(forked, hashOf(forked));

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(rows.get(0), rows.get(1), reforged));

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.SHARED_PREDECESSOR);
		assertThat(result.getVerifiedCount()).isEqualTo(1);
		assertThat(result.getFailedPosition()).contains(1);
	}

	@Test
	void danglingPrevHashIsMissingPredecessor() {
		List<ChainRow> rows = threeChain();
		ChainRow dangling = withEventHash(withPrevHash(rows.get(2), DANGLING),
				"1".repeat(64));

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(rows.get(0), rows.get(1), dangling));

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.MISSING_PREDECESSOR);
		assertThat(result.getFailedEventId()).contains(ID3);
		assertThat(result.getVerifiedCount()).isEqualTo(2);
		assertThat(result.getFailedPosition()).isEmpty();
	}

	@Test
	void missingGenesisIsDetected() {
		ChainRow orphan = validRow(ID1, AuditEventType.AUTH_LOGIN_SUCCEEDED, ACTOR,
				null, null, FIRST, null, HEAD);

		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of(orphan));

		assertInvalidReason(result, ID1, FailureReason.MISSING_GENESIS);
	}

	@Test
	void twinGenesisIsDetected() {
		ChainRow first = validRow(ID1, AuditEventType.AUTH_USER_REGISTERED, ACTOR,
				null, null, FIRST, null, null);
		ChainRow second = validRow(ID2, AuditEventType.AUTH_LOGIN_SUCCEEDED, ACTOR,
				null, null, FIRST.plusSeconds(1), null, null);

		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of(first, second));

		assertInvalidReason(result, ID2, FailureReason.MULTIPLE_GENESIS);
	}

	@Test
	void sharedPredecessorForkIsDetected() {
		List<ChainRow> rows = threeChain();
		ChainRow fork = validRow(ID4, AuditEventType.AUTH_LOGIN_SUCCEEDED, ACTOR,
				null, null, FIRST.plusSeconds(30), null, rows.get(0).eventHash());

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(rows.get(0), rows.get(1), rows.get(2), fork));

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.SHARED_PREDECESSOR);
		assertThat(result.getVerifiedCount()).isEqualTo(1);
		assertThat(result.getFailedPosition()).contains(1);
	}

	@Test
	void detachedCycleIsUnreachable() {
		ChainRow genesis = validRow(ID1, AuditEventType.AUTH_USER_REGISTERED, ACTOR,
				null, null, FIRST, null, null);
		String hashB = "b".repeat(64);
		String hashC = "c".repeat(64);
		ChainRow memberB = new ChainRow(ID2, AuditEventType.AUTH_LOGIN_SUCCEEDED, ACTOR,
				null, null, FIRST.plusSeconds(1), null, hashC, hashB);
		ChainRow memberC = new ChainRow(ID3, AuditEventType.AUTH_LOGIN_SUCCEEDED, ACTOR,
				null, null, FIRST.plusSeconds(2), null, hashB, hashC);

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(genesis, memberB, memberC));

		assertThat(result.isValid()).isFalse();
		assertThat(result.getFailureReason()).contains(FailureReason.UNREACHABLE_ROW);
		assertThat(result.getFailedEventId()).contains(ID2);
		assertThat(result.getVerifiedCount()).isEqualTo(1);
	}

	@Test
	void scrambledTimestampsStillVerify() {
		List<ChainRow> rows = threeChain();
		ChainRow early = validRow(ID1, AuditEventType.AUTH_USER_REGISTERED, ACTOR,
				"USER", ACTOR, FIRST.plusSeconds(3600), null, null);
		ChainRow middle = validRow(ID2, AuditEventType.PRIVACY_PREFERENCE_UPSERTED, ACTOR,
				"USER", ACTOR, FIRST, "{\"conceptCode\":\"LOCATION\"}", early.eventHash());
		ChainRow late = validRow(ID3, AuditEventType.AUTH_LOGIN_SUCCEEDED, ACTOR,
				null, null, FIRST.plusSeconds(60), null, middle.eventHash());
		assertThat(rows.get(0).eventHash()).isNotEqualTo(early.eventHash());

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(early, middle, late));

		assertThat(result.isValid()).isTrue();
		assertThat(result.getVerifiedCount()).isEqualTo(3);
	}

	@Test
	void uppercaseHashIsMalformed() {
		List<ChainRow> rows = threeChain();
		ChainRow upper = withEventHash(rows.get(0),
				rows.get(0).eventHash().toUpperCase());

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(upper, rows.get(1), rows.get(2)));

		assertInvalidReason(result, ID1, FailureReason.MALFORMED_HASH);
	}

	@Test
	void shortAndNonHexHashesAreMalformed() {
		ChainRow shortHash = new ChainRow(ID1, AuditEventType.AUTH_USER_REGISTERED,
				ACTOR, null, null, FIRST, null, null, "abc");
		ChainRow nonHex = new ChainRow(ID1, AuditEventType.AUTH_USER_REGISTERED,
				ACTOR, null, null, FIRST, null, null, "z".repeat(64));

		assertInvalidReason(AuditVerificationService.verifyChain(List.of(shortHash)),
				ID1, FailureReason.MALFORMED_HASH);
		assertInvalidReason(AuditVerificationService.verifyChain(List.of(nonHex)),
				ID1, FailureReason.MALFORMED_HASH);
	}

	@Test
	void microsecondTruncationVerifies() {
		Instant nanos = Instant.parse("2026-09-18T10:00:00.123456789Z");
		ChainRow precise = validRow(ID1, AuditEventType.AUTH_LOGIN_SUCCEEDED, ACTOR,
				null, null, nanos, null, null);

		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of(precise));

		assertThat(result.isValid()).isTrue();
		assertThat(precise.occurredAt()).isEqualTo(nanos);
	}

	@Test
	void escapingInMetadataVerifies() {
		ChainRow genesis = validRow(ID1, AuditEventType.POLICY_REGISTERED, ACTOR,
				"POLICY", RESOURCE, FIRST, "{\"note\":\"a|b\\\\c\"}", null);

		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of(genesis));

		assertThat(result.isValid()).isTrue();
	}

	@Test
	void repeatedVerificationIsDeterministic() {
		List<ChainRow> rows = threeChain();

		AuditVerificationResult first = AuditVerificationService.verifyChain(rows);
		AuditVerificationResult second = AuditVerificationService.verifyChain(rows);
		AuditVerificationResult third = AuditVerificationService.verifyChain(
				List.of(rows.get(2), rows.get(0), rows.get(1)));

		assertThat(first).isEqualTo(second);
		assertThat(first).isEqualTo(third);
		assertThat(first.toString()).isEqualTo(second.toString());
	}

	@Test
	void malformedBeatsStructuralFailures() {
		ChainRow first = validRow(ID1, AuditEventType.AUTH_USER_REGISTERED, ACTOR,
				null, null, FIRST, null, null);
		ChainRow twin = new ChainRow(ID2, AuditEventType.AUTH_LOGIN_SUCCEEDED,
				ACTOR, null, null, FIRST.plusSeconds(1), null, null, "short");

		AuditVerificationResult result = AuditVerificationService.verifyChain(List.of(first, twin));

		assertInvalidReason(result, ID2, FailureReason.MALFORMED_HASH);
	}

	@Test
	void earliestChainFailureIsReported() {
		List<ChainRow> rows = threeChain();
		ChainRow firstBad = withMetadata(rows.get(1), "{\"conceptCode\":\"COOKIES\"}");
		ChainRow secondBad = withMetadata(rows.get(2), "{\"conceptCode\":\"ARBITRATION\"}");

		AuditVerificationResult result = AuditVerificationService.verifyChain(
				List.of(rows.get(0), firstBad, secondBad));

		assertInvalid(result, 1, ID2, 1, FailureReason.HASH_MISMATCH);
	}

	private List<ChainRow> threeChain() {
		ChainRow genesis = validRow(ID1, AuditEventType.AUTH_USER_REGISTERED, ACTOR,
				"USER", ACTOR, FIRST, null, null);
		ChainRow middle = validRow(ID2, AuditEventType.PRIVACY_PREFERENCE_UPSERTED, ACTOR,
				"USER", ACTOR, FIRST.plusSeconds(60), "{\"conceptCode\":\"LOCATION\"}",
				genesis.eventHash());
		ChainRow tail = validRow(ID3, AuditEventType.AUTH_LOGIN_SUCCEEDED, ACTOR,
				null, null, FIRST.plusSeconds(120), null, middle.eventHash());
		return List.of(genesis, middle, tail);
	}

	private static ChainRow validRow(UUID id, AuditEventType type, UUID actor,
			String resourceType, UUID resourceId, Instant at, String metadata,
			String prevHash) {
		return new ChainRow(id, type, actor, resourceType, resourceId, at, metadata,
				prevHash, hashOf(id, type, at, actor, resourceType, resourceId, metadata,
						prevHash));
	}

	private static String hashOf(ChainRow row) {
		return hashOf(row.id(), row.eventType(), row.occurredAt(), row.actorUserId(),
				row.resourceType(), row.resourceId(), row.metadata(), row.prevHash());
	}

	private static String hashOf(UUID id, AuditEventType type, Instant at, UUID actor,
			String resourceType, UUID resourceId, String metadata, String prevHash) {
		return AuditChain.canonicalHash(AuditChain.CANONICAL_FORMAT_VERSION,
				type.name(), actor, resourceType, resourceId, at, metadata, prevHash);
	}

	private static ChainRow withMetadata(ChainRow row, String metadata) {
		return new ChainRow(row.id(), row.eventType(), row.actorUserId(),
				row.resourceType(), row.resourceId(), row.occurredAt(), metadata,
				row.prevHash(), row.eventHash());
	}

	private static ChainRow withPrevHash(ChainRow row, String prevHash) {
		return new ChainRow(row.id(), row.eventType(), row.actorUserId(),
				row.resourceType(), row.resourceId(), row.occurredAt(), row.metadata(),
				prevHash, row.eventHash());
	}

	private static ChainRow withEventHash(ChainRow row, String eventHash) {
		return new ChainRow(row.id(), row.eventType(), row.actorUserId(),
				row.resourceType(), row.resourceId(), row.occurredAt(), row.metadata(),
				row.prevHash(), eventHash);
	}

	private static void assertInvalid(AuditVerificationResult result, long verifiedCount,
			UUID failedId, int failedPosition, FailureReason reason) {
		assertThat(result.isValid()).isFalse();
		assertThat(result.getVerifiedCount()).isEqualTo(verifiedCount);
		assertThat(result.getFailedEventId()).contains(failedId);
		assertThat(result.getFailedPosition()).contains(failedPosition);
		assertThat(result.getFailureReason()).contains(reason);
	}

	private static void assertInvalidReason(AuditVerificationResult result, UUID failedId,
			FailureReason reason) {
		assertThat(result.isValid()).isFalse();
		assertThat(result.getVerifiedCount()).isZero();
		assertThat(result.getFailedEventId()).contains(failedId);
		assertThat(result.getFailedPosition()).isEmpty();
		assertThat(result.getFailureReason()).contains(reason);
	}
}
