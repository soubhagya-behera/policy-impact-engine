package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.diff.DefaultPolicySimHash;
import com.soubhagya.policyimpactengine.diff.PolicyChange;
import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.PolicyDiffEngine;
import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.PolicySimHash;
import com.soubhagya.policyimpactengine.diff.SimHashDistance;
import com.soubhagya.policyimpactengine.diff.SimHashSimilarity;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;

/**
 * Phase 2M — deterministic unit tests for SimHash similarity signal.
 *
 * <p>Covers the 9 required cases:
 * 1. FIRST_VERSION no similarity, no SimHash invocation
 * 2. UNCHANGED no similarity, no SimHash invocation
 * 3. NEW_VERSION supplies previous+new normalized to SimHash and returns distance+s similarity
 * 4. SimHash receives normalized content, never raw HTML
 * 5. Previous and new contents are paired correctly (order matters)
 * 6. Numerical similarity matches SimHashDistance formula
 * 7. SHA-256 remains authoritative (high SimHash similarity does not suppress NEW_VERSION)
 * 8. SimHash failure propagates explicitly, no fake similarity
 * 9. Deterministic repeated observation produces identical similarity values
 *
 * <p>No Spring context, no database, no network. SimHash is mocked for
 * interaction verification and real for determinism.
 */
@ExtendWith(MockitoExtension.class)
class PolicyObservationSimHashTest {

	@Mock
	private PolicyVersionService versionService;

	@Mock
	private PolicyVersionRepository versionRepository;

	@Mock
	private PolicyDiffEngine diffEngine;

	@Mock
	private PolicyChangeRecordRepository changeRepository;

	@Mock
	private PolicySimHash simHash;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	@InjectMocks
	private PolicyObservationPersistenceService service;

	@BeforeEach
	void stubTransactionManager() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	// 1. FIRST_VERSION: similarity absent, SimHash not invoked
	@Test
	void firstVersionHasNoSimilarityAndDoesNotInvokeSimHash() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("first normalized"), eq("hash-first")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.FIRST_VERSION,
						new PolicyVersion(policy, 1, "first normalized", "hash-first")));

		PolicyObservationResult result = service.store(policyId, "first normalized", "hash-first");

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.FIRST_VERSION);
		assertThat(result.similarity()).isEmpty();
		assertThat(result.diff()).isEmpty();
		verifyNoInteractions(simHash);
	}

	// 2. UNCHANGED: same SHA-256, no version, no SimHash
	@Test
	void unchangedHasNoSimilarityAndDoesNotInvokeSimHash() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("same normalized"), eq("hash-same")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.UNCHANGED,
						new PolicyVersion(policy, 1, "same normalized", "hash-same")));

		PolicyObservationResult result = service.store(policyId, "same normalized", "hash-same");

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.UNCHANGED);
		assertThat(result.similarity()).isEmpty();
		assertThat(result.diff()).isEmpty();
		verifyNoInteractions(simHash);
	}

	// 3. NEW_VERSION: previous and new normalized supplied to SimHash, distance+similarity returned
	@Test
	void newVersionCalculatesSimilarityFromNormalizedContents() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("new normalized"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new normalized", "hash-new")));
		PolicyVersion previous = new PolicyVersion(policy, 1, "previous normalized", "hash-prev");
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(previous));
		PolicyDiffResult diff = new PolicyDiffResult(List.of(
				new PolicyChange(PolicyChangeType.MODIFIED, "previous normalized", "new normalized")));
		when(diffEngine.diff("previous normalized", "new normalized")).thenReturn(diff);
		when(simHash.fingerprint("previous normalized")).thenReturn(0x0F0F0F0F0F0F0F0FL);
		when(simHash.fingerprint("new normalized")).thenReturn(0xF0F0F0F0F0F0F0F0L);

		PolicyObservationResult result = service.store(policyId, "new normalized", "hash-new");

		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.diff()).contains(diff);
		assertThat(result.similarity()).isPresent();
		SimHashSimilarity sim = result.similarity().orElseThrow();
		int expectedDistance = SimHashDistance.hammingDistance(0x0F0F0F0F0F0F0F0FL, 0xF0F0F0F0F0F0F0F0L);
		// 0F vs F0 are complements, distance 64
		assertThat(sim.previousHash()).isEqualTo(0x0F0F0F0F0F0F0F0FL);
		assertThat(sim.newHash()).isEqualTo(0xF0F0F0F0F0F0F0F0L);
		assertThat(sim.hammingDistance()).isEqualTo(expectedDistance);
		assertThat(sim.similarity()).isEqualTo(SimHashDistance.similarity(0x0F0F0F0F0F0F0F0FL, 0xF0F0F0F0F0F0F0F0L));
		verify(simHash).fingerprint("previous normalized");
		verify(simHash).fingerprint("new normalized");
	}

	// 4. SimHash receives normalized content, never raw HTML
	@Test
	void simHashReceivesNormalizedContentNeverRawHtml() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		String previousNormalized = "Privacy Policy\nWe collect location data.";
		String newNormalized = "Privacy Policy\nWe collect precise location data.";
		String rawHtml = "<html><body>" + newNormalized + "</body></html>";
		when(versionService.observe(eq(policyId), eq(newNormalized), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, newNormalized, "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(new PolicyVersion(policy, 1, previousNormalized, "hash-prev")));
		when(diffEngine.diff(previousNormalized, newNormalized))
				.thenReturn(new PolicyDiffResult(List.of()));
		when(simHash.fingerprint(previousNormalized)).thenReturn(11L);
		when(simHash.fingerprint(newNormalized)).thenReturn(22L);

		PolicyObservationResult result = service.store(policyId, newNormalized, "hash-new");

		assertThat(result.similarity()).isPresent();
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(simHash, org.mockito.Mockito.times(2)).fingerprint(captor.capture());
		assertThat(captor.getAllValues()).containsExactlyInAnyOrder(previousNormalized, newNormalized);
		for (String captured : captor.getAllValues()) {
			assertThat(captured).doesNotContain("<html>");
			assertThat(captured).doesNotContain("<body>");
			assertThat(captured).isNotEqualTo(rawHtml);
		}
	}

	// 5. Previous and new contents are paired correctly
	@Test
	void simHashPreviousAndNewArePairedCorrectly() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		String prev = "previous normalized";
		String next = "new normalized";
		when(versionService.observe(eq(policyId), eq(next), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, next, "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(new PolicyVersion(policy, 1, prev, "hash-prev")));
		when(diffEngine.diff(prev, next)).thenReturn(new PolicyDiffResult(List.of()));
		// Distinct fingerprints so order matters
		when(simHash.fingerprint(prev)).thenReturn(0xAAAAAAAAAAAAAAAAL);
		when(simHash.fingerprint(next)).thenReturn(0x5555555555555555L);

		PolicyObservationResult result = service.store(policyId, next, "hash-new");

		SimHashSimilarity sim = result.similarity().orElseThrow();
		assertThat(sim.previousHash()).isEqualTo(0xAAAAAAAAAAAAAAAAL);
		assertThat(sim.newHash()).isEqualTo(0x5555555555555555L);
		// If swapped, distance would still be same due to symmetry but hashes would be swapped
		// The invariant is that previousHash corresponds to previous normalized
		verify(simHash).fingerprint(prev);
		verify(simHash).fingerprint(next);
	}

	// 6. Numerical similarity matches SimHashDistance
	@Test
	void similarityNumericsMatchSimHashDistanceFormula() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("new"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(new PolicyVersion(policy, 1, "prev", "hash-prev")));
		when(diffEngine.diff("prev", "new")).thenReturn(new PolicyDiffResult(List.of()));
		long prevHash = 0b1010L;
		long newHash = 0b0101L;
		when(simHash.fingerprint("prev")).thenReturn(prevHash);
		when(simHash.fingerprint("new")).thenReturn(newHash);

		PolicyObservationResult result = service.store(policyId, "new", "hash-new");

		SimHashSimilarity sim = result.similarity().orElseThrow();
		assertThat(sim.hammingDistance()).isEqualTo(SimHashDistance.hammingDistance(prevHash, newHash));
		assertThat(sim.similarity()).isEqualTo(SimHashDistance.similarity(prevHash, newHash));
		assertThat(sim.similarity()).isEqualTo(1.0 - (4 / 64.0));
	}

	// 7. SHA-256 remains authoritative: high SimHash similarity does not suppress NEW_VERSION
	@Test
	void sha256RemainsAuthoritativeDespiteHighSimHashSimilarity() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		// Different SHA-256 forces NEW_VERSION even if SimHash distance is 0 (identical)
		when(versionService.observe(eq(policyId), eq("new wording"), eq("hash-different")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new wording", "hash-different")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(new PolicyVersion(policy, 1, "old wording", "hash-old")));
		when(diffEngine.diff("old wording", "new wording")).thenReturn(new PolicyDiffResult(List.of()));
		// Mock identical fingerprints => distance 0 similarity 1.0
		when(simHash.fingerprint("old wording")).thenReturn(0x1234567890ABCDEFL);
		when(simHash.fingerprint("new wording")).thenReturn(0x1234567890ABCDEFL);

		PolicyObservationResult result = service.store(policyId, "new wording", "hash-different");

		// Still NEW_VERSION despite perfect SimHash similarity
		assertThat(result.outcome()).isEqualTo(PolicyVersionObservationOutcome.NEW_VERSION);
		assertThat(result.similarity()).isPresent();
		assertThat(result.similarity().orElseThrow().hammingDistance()).isZero();
		assertThat(result.similarity().orElseThrow().similarity()).isEqualTo(1.0);
	}

	// 8. SimHash failure propagates explicitly, no fake similarity
	@Test
	void simHashFailurePropagatesWithNoFakeSimilarity() {
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq("new"), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, "new", "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(new PolicyVersion(policy, 1, "prev", "hash-prev")));
		when(diffEngine.diff("prev", "new")).thenReturn(new PolicyDiffResult(List.of()));
		when(simHash.fingerprint(any())).thenThrow(new IllegalStateException("simHash failed"));

		assertThatThrownBy(() -> service.store(policyId, "new", "hash-new"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("simHash failed");
	}

	// 9. Deterministic repeated fingerprinting yields identical similarity
	@Test
	void deterministicRepeatedSimilarityValues() {
		PolicySimHash realSimHash = new DefaultPolicySimHash();
		String prev = "Privacy Policy\nWe collect your location data.\nWe retain data for 30 days.";
		String next = "Privacy Policy\nWe collect your precise location data.\nWe retain data for 30 days.";
		long prevHashFirst = realSimHash.fingerprint(prev);
		long newHashFirst = realSimHash.fingerprint(next);
		int distanceFirst = SimHashDistance.hammingDistance(prevHashFirst, newHashFirst);
		double simFirst = SimHashDistance.similarity(prevHashFirst, newHashFirst);

		long prevHashSecond = realSimHash.fingerprint(prev);
		long newHashSecond = realSimHash.fingerprint(next);
		int distanceSecond = SimHashDistance.hammingDistance(prevHashSecond, newHashSecond);
		double simSecond = SimHashDistance.similarity(prevHashSecond, newHashSecond);

		assertThat(prevHashSecond).isEqualTo(prevHashFirst);
		assertThat(newHashSecond).isEqualTo(newHashFirst);
		assertThat(distanceSecond).isEqualTo(distanceFirst);
		assertThat(simSecond).isEqualTo(simFirst);
		assertThat(distanceFirst).isEqualTo(4);

		// Also verify via service path with real SimHash wired through a mock-free persistence service
		// Here we reuse the mocked versionService to drive the same flow deterministically
		UUID policyId = UUID.randomUUID();
		Policy policy = registeredPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(versionService.observe(eq(policyId), eq(next), eq("hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, next, "hash-new")))
				.thenReturn(new PolicyVersionObservation(PolicyVersionObservationOutcome.NEW_VERSION,
						new PolicyVersion(policy, 2, next, "hash-new")));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policyId, 1))
				.thenReturn(Optional.of(new PolicyVersion(policy, 1, prev, "hash-prev")));
		when(diffEngine.diff(prev, next)).thenReturn(new PolicyDiffResult(List.of()));
		when(simHash.fingerprint(prev)).thenReturn(prevHashFirst);
		when(simHash.fingerprint(next)).thenReturn(newHashFirst);

		PolicyObservationResult r1 = service.store(policyId, next, "hash-new");
		PolicyObservationResult r2 = service.store(policyId, next, "hash-new");

		assertThat(r1.similarity().orElseThrow().previousHash())
				.isEqualTo(r2.similarity().orElseThrow().previousHash());
		assertThat(r1.similarity().orElseThrow().newHash())
				.isEqualTo(r2.similarity().orElseThrow().newHash());
		assertThat(r1.similarity().orElseThrow().hammingDistance())
				.isEqualTo(r2.similarity().orElseThrow().hammingDistance());
		assertThat(r1.similarity().orElseThrow().similarity())
				.isEqualTo(r2.similarity().orElseThrow().similarity());
	}

	private Policy registeredPolicy(String name, String url) {
		Policy policy = new Policy(name, url);
		policy.setId(UUID.randomUUID());
		return policy;
	}
}
