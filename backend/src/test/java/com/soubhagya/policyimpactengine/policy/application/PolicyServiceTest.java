package com.soubhagya.policyimpactengine.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.web.dto.PolicyResponse;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

	@Mock
	private PolicyRepository repository;

	@Mock
	private UserRepository userRepository;

	// Phase 11C: PolicyService gained assignment-transaction and audit
	// dependencies; mocked so registration/read tests stay isolated.
	@Mock
	private org.springframework.transaction.PlatformTransactionManager transactionManager;

	@Mock
	private com.soubhagya.policyimpactengine.audit.application.AuditService auditService;

	@InjectMocks
	private PolicyService service;

	@Test
	void registerValidatesAssignsOwnerSavesAndReturnsResponse() {
		User owner = userWithId();
		Policy saved = persistedPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
		when(repository.save(any(Policy.class))).thenReturn(saved);

		PolicyResponse response = service.register(owner.getId(),
				"Acme Privacy Policy", "https://example.com/privacy");

		assertThat(response.id()).isEqualTo(saved.getId());
		assertThat(response.name()).isEqualTo("Acme Privacy Policy");
		assertThat(response.url()).isEqualTo("https://example.com/privacy");
		assertThat(response.status()).isEqualTo("ACTIVE");
		assertThat(response.createdAt()).isEqualTo(saved.getCreatedAt());
		assertThat(response.updatedAt()).isEqualTo(saved.getUpdatedAt());

		ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().getOwner()).isSameAs(owner);
	}

	@Test
	void registerPersistsValidatedUrl() {
		User owner = userWithId();
		when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
		when(repository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		service.register(owner.getId(), "Acme Privacy Policy", "  https://example.com/privacy  ");

		ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().getUrl()).isEqualTo("https://example.com/privacy");
		assertThat(captor.getValue().getOwner()).isSameAs(owner);
	}

	@Test
	void registerRejectsInvalidUrlWithoutSaving() {
		assertThatThrownBy(() -> service.register(UUID.randomUUID(),
				"Acme Privacy Policy", "http://example.com/privacy"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("https");

		verifyNoInteractions(repository);
		verifyNoInteractions(userRepository);
	}

	@Test
	void registerRejectsUnknownUser() {
		UUID unknown = UUID.randomUUID();
		when(userRepository.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.register(unknown,
				"Acme Privacy Policy", "https://example.com/privacy"))
				.isInstanceOf(NoSuchElementException.class);

		verifyNoInteractions(repository);
	}

	@Test
	void getReturnsOwnedPolicy() {
		User owner = userWithId();
		Policy saved = persistedPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(repository.findByIdAndOwner_Id(saved.getId(), owner.getId()))
				.thenReturn(Optional.of(saved));

		PolicyResponse response = service.get(owner.getId(), saved.getId());

		assertThat(response.id()).isEqualTo(saved.getId());
		assertThat(response.url()).isEqualTo("https://example.com/privacy");
	}

	@Test
	void getThrowsWhenMissingOrForeign() {
		User owner = userWithId();
		UUID id = UUID.randomUUID();
		when(repository.findByIdAndOwner_Id(id, owner.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(owner.getId(), id))
				.isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void listReturnsOnlyOwnedPolicies() {
		User owner = userWithId();
		when(repository.findByOwner_IdOrderByCreatedAtAscIdAsc(owner.getId())).thenReturn(List.of(
				persistedPolicy("First Policy", "https://first.example/privacy"),
				persistedPolicy("Second Policy", "https://second.example/terms")));

		List<PolicyResponse> responses = service.list(owner.getId());

		assertThat(responses).hasSize(2);
		assertThat(responses).extracting(PolicyResponse::name)
				.containsExactly("First Policy", "Second Policy");
	}

	@Test
	void nullUserIdsAreRejected() {
		assertThatThrownBy(() -> service.register(null, "P", "https://example.com/p"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.get(null, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.list(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private Policy persistedPolicy(String name, String url) {
		Policy policy = new Policy(name, url);
		policy.setId(UUID.randomUUID());
		policy.setCreatedAt(Instant.parse("2026-09-14T10:00:00Z"));
		policy.setUpdatedAt(Instant.parse("2026-09-14T10:00:00Z"));
		return policy;
	}

	private User userWithId() {
		try {
			User user = new User();
			java.lang.reflect.Field id = User.class.getDeclaredField("id");
			id.setAccessible(true);
			id.set(user, UUID.randomUUID());
			return user;
		}
		catch (ReflectiveOperationException reflectionFailure) {
			throw new IllegalStateException("Cannot assign user id", reflectionFailure);
		}
	}

}
