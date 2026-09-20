package com.soubhagya.policyimpactengine.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soubhagya.policyimpactengine.diff.PolicyDiffResult;
import com.soubhagya.policyimpactengine.diff.SimHashSimilarity;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.policy.application.PolicyObservationResult;
import com.soubhagya.policyimpactengine.policy.application.PolicyVersionObservationOutcome;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyStatus;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.user.domain.User;

/**
 * Phase 10B-1 — unit tests for {@link NotificationFanOutService}.
 *
 * <p>No Spring context, no database. Covers the full eligible flow,
 * every silent case (FIRST_VERSION, UNCHANGED, inactive policy,
 * unowned policy), version ID derivation through the existing
 * repository query, and argument validation.
 */
@ExtendWith(MockitoExtension.class)
class NotificationFanOutServiceTest {

	@Mock
	private PolicyRepository policyRepository;

	@Mock
	private PolicyVersionRepository versionRepository;

	@Mock
	private ImpactAssessmentService assessmentService;

	@Mock
	private RecommendationService recommendationService;

	@InjectMocks
	private NotificationFanOutService fanOutService;

	@Test
	void newVersionWithOwnerRunsFullFlow() {
		Policy policy = ownedPolicy(PolicyStatus.ACTIVE);
		PolicyVersion version = version(policy, 2);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 2))
				.thenReturn(Optional.of(version));

		fanOutService.fanOut(policy.getId(), result(policy.getId(),
				PolicyVersionObservationOutcome.NEW_VERSION, 2));

		verify(versionRepository).findByPolicy_IdAndVersionNumber(policy.getId(), 2);
		verify(assessmentService).getOrCreateAssessment(policy.getOwner().getId(), version.getId());
		verify(recommendationService)
				.getOrCreateRecommendations(policy.getOwner().getId(), version.getId());
	}

	@Test
	void firstVersionDoesNotFanOut() {
		Policy policy = ownedPolicy(PolicyStatus.ACTIVE);

		fanOutService.fanOut(policy.getId(), result(policy.getId(),
				PolicyVersionObservationOutcome.FIRST_VERSION, 1));

		verifyNoInteractions(policyRepository, versionRepository, assessmentService,
				recommendationService);
	}

	@Test
	void unchangedDoesNotFanOut() {
		Policy policy = ownedPolicy(PolicyStatus.ACTIVE);

		fanOutService.fanOut(policy.getId(), result(policy.getId(),
				PolicyVersionObservationOutcome.UNCHANGED, 3));

		verifyNoInteractions(policyRepository, versionRepository, assessmentService,
				recommendationService);
	}

	@Test
	void inactivePolicyDoesNotFanOut() {
		Policy policy = ownedPolicy(PolicyStatus.ARCHIVED);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

		fanOutService.fanOut(policy.getId(), result(policy.getId(),
				PolicyVersionObservationOutcome.NEW_VERSION, 2));

		verify(policyRepository).findById(policy.getId());
		verifyNoInteractions(versionRepository, assessmentService, recommendationService);
	}

	@Test
	void unownedPolicyDoesNotFanOut() {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setId(UUID.randomUUID());
		policy.setStatus(PolicyStatus.ACTIVE);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

		fanOutService.fanOut(policy.getId(), result(policy.getId(),
				PolicyVersionObservationOutcome.NEW_VERSION, 2));

		verify(policyRepository).findById(policy.getId());
		verifyNoInteractions(versionRepository, assessmentService, recommendationService);
	}

	@Test
	void versionIdDerivedThroughExistingQuery() {
		Policy policy = ownedPolicy(PolicyStatus.ACTIVE);
		PolicyVersion version = version(policy, 5);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 5))
				.thenReturn(Optional.of(version));

		fanOutService.fanOut(policy.getId(), result(policy.getId(),
				PolicyVersionObservationOutcome.NEW_VERSION, 5));

		verify(assessmentService).getOrCreateAssessment(eq(policy.getOwner().getId()),
				eq(version.getId()));
		verify(recommendationService).getOrCreateRecommendations(eq(policy.getOwner().getId()),
				eq(version.getId()));
	}

	@Test
	void missingVersionPropagates() {
		Policy policy = ownedPolicy(PolicyStatus.ACTIVE);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(versionRepository.findByPolicy_IdAndVersionNumber(policy.getId(), 2))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> fanOutService.fanOut(policy.getId(), result(policy.getId(),
				PolicyVersionObservationOutcome.NEW_VERSION, 2)))
				.isInstanceOf(NoSuchElementException.class);
		verify(assessmentService, never()).getOrCreateAssessment(any(), any());
		verify(recommendationService, never()).getOrCreateRecommendations(any(), any());
	}

	@Test
	void nullAndMismatchedArgumentsRejected() {
		UUID policyId = UUID.randomUUID();
		assertThatThrownBy(() -> fanOutService.fanOut(null, result(policyId,
				PolicyVersionObservationOutcome.NEW_VERSION, 2)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> fanOutService.fanOut(policyId, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> fanOutService.fanOut(policyId, result(UUID.randomUUID(),
				PolicyVersionObservationOutcome.NEW_VERSION, 2)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NotificationFanOutService(null, versionRepository,
				assessmentService, recommendationService))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NotificationFanOutService(policyRepository, null,
				assessmentService, recommendationService))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private PolicyObservationResult result(UUID policyId,
			PolicyVersionObservationOutcome outcome, int versionNumber) {
		return new PolicyObservationResult(policyId, outcome, versionNumber, "hash",
				Optional.of(new PolicyDiffResult(java.util.List.of())),
				Optional.of(new SimHashSimilarity(0L, 0L, 0, 1.0)));
	}

	private Policy ownedPolicy(PolicyStatus status) {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setId(UUID.randomUUID());
		policy.setStatus(status);
		policy.setOwner(userWithId());
		return policy;
	}

	private PolicyVersion version(Policy policy, int number) {
		try {
			PolicyVersion version = new PolicyVersion(policy, number, "content-" + number,
					"hash-" + number);
			Field id = PolicyVersion.class.getDeclaredField("id");
			id.setAccessible(true);
			id.set(version, UUID.randomUUID());
			return version;
		}
		catch (ReflectiveOperationException reflectionFailure) {
			throw new IllegalStateException("Cannot assign version id", reflectionFailure);
		}
	}

	private User userWithId() {
		try {
			User user = new User();
			Field id = User.class.getDeclaredField("id");
			id.setAccessible(true);
			id.set(user, UUID.randomUUID());
			return user;
		}
		catch (ReflectiveOperationException reflectionFailure) {
			throw new IllegalStateException("Cannot assign user id", reflectionFailure);
		}
	}
}
