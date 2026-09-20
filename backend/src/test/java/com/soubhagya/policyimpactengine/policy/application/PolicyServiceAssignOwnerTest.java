package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 10B-1 — unit tests for {@link PolicyService#assignOwner}.
 *
 * <p>No Spring context, no database. Covers unknown policy, unknown
 * user, null-owner assignment, same-owner idempotency, and
 * different-owner rejection.
 */
@ExtendWith(MockitoExtension.class)
class PolicyServiceAssignOwnerTest {

	@Mock
	private PolicyRepository policyRepository;

	@Mock
	private UserRepository userRepository;

	@InjectMocks
	private PolicyService service;

	@Test
	void unknownPolicyRejected() {
		UUID policyId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.assignOwner(userId, policyId))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining(policyId.toString());
		verify(policyRepository, never()).save(any());
	}

	@Test
	void unknownUserRejected() {
		Policy policy = policy();
		User missingId = userWithId();
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(userRepository.findById(missingId.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.assignOwner(missingId.getId(), policy.getId()))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining(missingId.getId().toString());
		verify(policyRepository, never()).save(any());
		assertThat(policy.getOwner()).isNull();
	}

	@Test
	void nullOwnerAssignment() {
		Policy policy = policy();
		User user = userWithId();
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
		when(policyRepository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		service.assignOwner(user.getId(), policy.getId());

		assertThat(policy.getOwner()).isSameAs(user);
		verify(policyRepository).save(policy);
	}

	@Test
	void sameOwnerAssignmentIsIdempotentNoOp() {
		Policy policy = policy();
		User user = userWithId();
		policy.setOwner(user);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

		service.assignOwner(user.getId(), policy.getId());

		assertThat(policy.getOwner()).isSameAs(user);
		verify(policyRepository, never()).save(any());
	}

	@Test
	void differentOwnerAssignmentRejected() {
		Policy policy = policy();
		User owner = userWithId();
		User other = userWithId();
		policy.setOwner(owner);
		when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
		when(userRepository.findById(other.getId())).thenReturn(Optional.of(other));

		assertThatThrownBy(() -> service.assignOwner(other.getId(), policy.getId()))
				.isInstanceOf(IllegalStateException.class);
		assertThat(policy.getOwner()).isSameAs(owner);
		verify(policyRepository, never()).save(any());
	}

	@Test
	void nullIdsRejected() {
		assertThatThrownBy(() -> service.assignOwner(null, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.assignOwner(UUID.randomUUID(), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private Policy policy() {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		policy.setId(UUID.randomUUID());
		return policy;
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
