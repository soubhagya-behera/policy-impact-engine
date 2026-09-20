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
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

	@Mock
	private PolicyRepository repository;

	@Mock
	private UserRepository userRepository;

	@InjectMocks
	private PolicyService service;

	@Test
	void registerValidatesSavesAndReturnsResponse() {
		Policy saved = persistedPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(repository.save(any(Policy.class))).thenReturn(saved);

		PolicyResponse response = service.register("Acme Privacy Policy", "https://example.com/privacy");

		assertThat(response.id()).isEqualTo(saved.getId());
		assertThat(response.name()).isEqualTo("Acme Privacy Policy");
		assertThat(response.url()).isEqualTo("https://example.com/privacy");
		assertThat(response.status()).isEqualTo("ACTIVE");
		assertThat(response.createdAt()).isEqualTo(saved.getCreatedAt());
		assertThat(response.updatedAt()).isEqualTo(saved.getUpdatedAt());
	}

	@Test
	void registerPersistsValidatedUrl() {
		when(repository.save(any(Policy.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		service.register("Acme Privacy Policy", "  https://example.com/privacy  ");

		ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().getUrl()).isEqualTo("https://example.com/privacy");
	}

	@Test
	void registerRejectsInvalidUrlWithoutSaving() {
		assertThatThrownBy(() -> service.register("Acme Privacy Policy", "http://example.com/privacy"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("https");

		verifyNoInteractions(repository);
	}

	@Test
	void getByIdReturnsResponse() {
		Policy saved = persistedPolicy("Acme Privacy Policy", "https://example.com/privacy");
		when(repository.findById(saved.getId())).thenReturn(Optional.of(saved));

		PolicyResponse response = service.getById(saved.getId());

		assertThat(response.id()).isEqualTo(saved.getId());
		assertThat(response.url()).isEqualTo("https://example.com/privacy");
	}

	@Test
	void getByIdThrowsWhenMissing() {
		UUID id = UUID.randomUUID();
		when(repository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getById(id))
				.isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void listReturnsAllPolicies() {
		when(repository.findAll()).thenReturn(List.of(
				persistedPolicy("First Policy", "https://first.example/privacy"),
				persistedPolicy("Second Policy", "https://second.example/terms")));

		List<PolicyResponse> responses = service.list();

		assertThat(responses).hasSize(2);
		assertThat(responses).extracting(PolicyResponse::name)
				.containsExactly("First Policy", "Second Policy");
	}

	private Policy persistedPolicy(String name, String url) {
		Policy policy = new Policy(name, url);
		policy.setId(UUID.randomUUID());
		policy.setCreatedAt(Instant.parse("2026-09-14T10:00:00Z"));
		policy.setUpdatedAt(Instant.parse("2026-09-14T10:00:00Z"));
		return policy;
	}

}
