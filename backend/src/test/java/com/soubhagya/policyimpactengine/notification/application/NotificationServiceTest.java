package com.soubhagya.policyimpactengine.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.notification.domain.Notification;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.domain.User;

/**
 * Phase 10A — deterministic unit tests for {@link NotificationService}.
 *
 * <p>No Spring context, no database. A fixed clock pins emission and
 * mark-read timestamps; the transaction manager is a Mockito mock following
 * the established {@code TransactionTemplate} unit-test pattern.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

	@Mock
	private NotificationRepository notificationRepository;

	@Mock
	private ImpactAssessmentRepository assessmentRepository;

	@Mock
	private RecommendationRepository recommendationRepository;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private Clock clock;
	private NotificationService service;

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(NOW, ZoneOffset.UTC);
		service = new NotificationService(notificationRepository, assessmentRepository,
				recommendationRepository, transactionManager, clock);
	}

	private void givenTransaction() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
	}

	@Test
	void actionableRecommendationEmitsOneNotification() {
		User user = userWithId();
		ImpactAssessment assessment = assessmentWithId(user);
		Recommendation actionable = recommendation(assessment, "REC-SHARING-OPT-OUT", 2,
				RecommendationActionKind.OPT_OUT_SHARING, "THIRD_PARTY_SHARING");
		when(assessmentRepository.findById(assessment.getId()))
				.thenReturn(Optional.of(assessment));
		when(notificationRepository.findByAssessment_Id(assessment.getId()))
				.thenReturn(Optional.empty());
		when(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						assessment.getId()))
				.thenReturn(List.of(actionable));
		givenTransaction();
		when(notificationRepository.saveAndFlush(any(Notification.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		Optional<Notification> emitted = service.emitForAssessment(user.getId(), assessment.getId());

		assertThat(emitted).isPresent();
		assertThat(emitted.get().getAssessment()).isSameAs(assessment);
		assertThat(emitted.get().getCreatedAt()).isEqualTo(NOW);
		assertThat(emitted.get().getReadAt()).isNull();
	}

	@Test
	void noneRequiredOnlyEmitsNothing() {
		User user = userWithId();
		ImpactAssessment assessment = assessmentWithId(user);
		Recommendation closure = recommendation(assessment, "REC-NONE-REQUIRED", 4,
				RecommendationActionKind.NONE_REQUIRED, null);
		when(assessmentRepository.findById(assessment.getId()))
				.thenReturn(Optional.of(assessment));
		when(notificationRepository.findByAssessment_Id(assessment.getId()))
				.thenReturn(Optional.empty());
		when(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						assessment.getId()))
				.thenReturn(List.of(closure));

		Optional<Notification> emitted = service.emitForAssessment(user.getId(), assessment.getId());

		assertThat(emitted).isEmpty();
		verify(notificationRepository, never()).saveAndFlush(any());
		verify(transactionManager, never()).getTransaction(any());
	}

	@Test
	void emptyRecommendationsEmitNothing() {
		User user = userWithId();
		ImpactAssessment assessment = assessmentWithId(user);
		when(assessmentRepository.findById(assessment.getId()))
				.thenReturn(Optional.of(assessment));
		when(notificationRepository.findByAssessment_Id(assessment.getId()))
				.thenReturn(Optional.empty());
		when(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						assessment.getId()))
				.thenReturn(List.of());

		assertThat(service.emitForAssessment(user.getId(), assessment.getId())).isEmpty();
		verify(notificationRepository, never()).saveAndFlush(any());
	}

	@Test
	void mixedActionableAndClosureEmitsExactlyOne() {
		User user = userWithId();
		ImpactAssessment assessment = assessmentWithId(user);
		Recommendation actionable = recommendation(assessment, "REC-REVIEW-SETTINGS", 3,
				RecommendationActionKind.REVIEW_SETTINGS, "COOKIES");
		Recommendation closure = recommendation(assessment, "REC-NONE-REQUIRED", 4,
				RecommendationActionKind.NONE_REQUIRED, null);
		when(assessmentRepository.findById(assessment.getId()))
				.thenReturn(Optional.of(assessment));
		when(notificationRepository.findByAssessment_Id(assessment.getId()))
				.thenReturn(Optional.empty());
		when(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						assessment.getId()))
				.thenReturn(List.of(actionable, closure));
		givenTransaction();
		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		when(notificationRepository.saveAndFlush(saved.capture()))
				.thenAnswer(invocation -> invocation.getArgument(0));

		Optional<Notification> emitted = service.emitForAssessment(user.getId(), assessment.getId());

		assertThat(emitted).isPresent();
		assertThat(saved.getAllValues()).hasSize(1);
	}

	@Test
	void repeatedEmitReturnsExistingWithoutWriting() {
		User user = userWithId();
		ImpactAssessment assessment = assessmentWithId(user);
		Notification existing = new Notification(assessment, NOW.minusSeconds(30));
		when(assessmentRepository.findById(assessment.getId()))
				.thenReturn(Optional.of(assessment));
		when(notificationRepository.findByAssessment_Id(assessment.getId()))
				.thenReturn(Optional.of(existing));

		Optional<Notification> emitted = service.emitForAssessment(user.getId(), assessment.getId());

		assertThat(emitted).containsSame(existing);
		verify(notificationRepository, never()).saveAndFlush(any());
		verify(recommendationRepository, never())
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(any());
		verify(transactionManager, never()).getTransaction(any());
	}

	@Test
	void lostInsertRaceReReadsWinner() {
		User user = userWithId();
		ImpactAssessment assessment = assessmentWithId(user);
		Recommendation actionable = recommendation(assessment, "REC-SHARING-OPT-OUT", 2,
				RecommendationActionKind.OPT_OUT_SHARING, "THIRD_PARTY_SHARING");
		Notification winner = new Notification(assessment, NOW);
		when(assessmentRepository.findById(assessment.getId()))
				.thenReturn(Optional.of(assessment));
		when(notificationRepository.findByAssessment_Id(assessment.getId()))
				.thenReturn(Optional.empty(), Optional.of(winner));
		when(recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						assessment.getId()))
				.thenReturn(List.of(actionable));
		givenTransaction();
		when(notificationRepository.saveAndFlush(any(Notification.class)))
				.thenThrow(new DataIntegrityViolationException("uq_notification_assessment"));

		Optional<Notification> emitted = service.emitForAssessment(user.getId(), assessment.getId());

		assertThat(emitted).containsSame(winner);
	}

	@Test
	void crossUserEmitBehavesAsNotFound() {
		User owner = userWithId();
		ImpactAssessment assessment = assessmentWithId(owner);
		when(assessmentRepository.findById(assessment.getId()))
				.thenReturn(Optional.of(assessment));

		assertThatThrownBy(() -> service.emitForAssessment(UUID.randomUUID(), assessment.getId()))
				.isInstanceOf(IllegalArgumentException.class);
		verify(notificationRepository, never()).saveAndFlush(any());
	}

	@Test
	void unknownAssessmentBehavesAsNotFound() {
		User user = userWithId();
		when(assessmentRepository.findById(any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.emitForAssessment(user.getId(), UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
		verify(notificationRepository, never()).saveAndFlush(any());
	}

	@Test
	void markReadFirstCallStampsSecondIsNoOp() {
		User user = userWithId();
		ImpactAssessment assessment = assessmentWithId(user);
		Notification notification = notificationWithId(assessment, NOW.minusSeconds(60));
		when(notificationRepository.findById(notification.getId()))
				.thenReturn(Optional.of(notification));
		givenTransaction();
		when(notificationRepository.save(any(Notification.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		Notification read = service.markRead(user.getId(), notification.getId());

		assertThat(read.getReadAt()).isEqualTo(NOW);
		verify(notificationRepository).save(notification);

		Notification again = service.markRead(user.getId(), notification.getId());

		assertThat(again.getReadAt()).isEqualTo(NOW);
		verify(notificationRepository).save(notification);
	}

	@Test
	void crossUserMarkReadBehavesAsNotFound() {
		User owner = userWithId();
		ImpactAssessment assessment = assessmentWithId(owner);
		Notification notification = notificationWithId(assessment, NOW.minusSeconds(60));
		when(notificationRepository.findById(notification.getId()))
				.thenReturn(Optional.of(notification));

		assertThatThrownBy(() -> service.markRead(UUID.randomUUID(), notification.getId()))
				.isInstanceOf(IllegalArgumentException.class);
		verify(notificationRepository, never()).save(any());
	}

	@Test
	void listQueriesAreUserScoped() {
		User user = userWithId();
		Notification first = new Notification(assessmentWithId(user), NOW.minusSeconds(60));
		when(notificationRepository.findByAssessment_User_IdOrderByCreatedAtDesc(user.getId()))
				.thenReturn(List.of(first));
		when(notificationRepository
				.findByAssessment_User_IdAndReadAtIsNullOrderByCreatedAtDesc(user.getId()))
				.thenReturn(List.of(first));

		assertThat(service.listNotifications(user.getId())).containsExactly(first);
		assertThat(service.listUnreadNotifications(user.getId())).containsExactly(first);
	}

	@Test
	void nullIdsAreRejected() {
		assertThatThrownBy(() -> service.emitForAssessment(null, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.emitForAssessment(UUID.randomUUID(), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.markRead(null, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.markRead(UUID.randomUUID(), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.listNotifications(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.listUnreadNotifications(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private Recommendation recommendation(ImpactAssessment assessment, String ruleId, int order,
			RecommendationActionKind kind, String conceptCode) {
		return new Recommendation(assessment, ruleId, order, kind, conceptCode, 80,
				ImpactBand.CRITICAL, 1);
	}

	private ImpactAssessment assessmentWithId(User user) {
		Policy policy = new Policy("P " + UUID.randomUUID(),
				"https://example.com/" + UUID.randomUUID());
		PolicyVersion v1 = new PolicyVersion(policy, 1, "old", "hash-old");
		PolicyVersion v2 = new PolicyVersion(policy, 2, "new", "hash-new");
		return withId(new ImpactAssessment(user, v2, v1, 80, ImpactBand.HIGH, 1));
	}

	private Notification notificationWithId(ImpactAssessment assessment, Instant createdAt) {
		return withId(new Notification(assessment, createdAt));
	}

	private User userWithId() {
		return withId(new User());
	}

	private static <T> T withId(T entity) {
		try {
			Field id = entity.getClass().getDeclaredField("id");
			id.setAccessible(true);
			id.set(entity, UUID.randomUUID());
			return entity;
		}
		catch (ReflectiveOperationException reflectionFailure) {
			throw new IllegalStateException("Cannot assign entity id", reflectionFailure);
		}
	}
}
