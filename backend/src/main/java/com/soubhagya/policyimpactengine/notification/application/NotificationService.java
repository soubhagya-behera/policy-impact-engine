package com.soubhagya.policyimpactengine.notification.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.notification.domain.Notification;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.notification.web.dto.NotificationResponse;
import com.soubhagya.policyimpactengine.recommendation.FrozenRecommendationRules;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;

/**
 * Phase 10A — service-layer in-app notification emission over the user's
 * personalized {@link ImpactAssessment}.
 *
 * <p>Emission happens exactly once per assessment that produced actionable
 * impact: the assessment's persisted recommendation rows are read, and a
 * notification is recorded if and only if at least one row carries a rule
 * code other than {@code REC-NONE-REQUIRED}. The rule is stated in terms of
 * persisted rule codes rather than aggregate scores so it stays correct
 * under future rule versions without redefinition; the closure row by
 * construction never triggers emission.
 *
 * <p>Separate short transactions, mirroring Phase 2Q/2R: emission runs in
 * its own write transaction after recommendations commit, so an emission
 * failure never rolls back already committed assessments or
 * recommendations — a later emit heals the gap idempotently. Idempotency
 * per assessment is backed by the UNIQUE(assessment_id) index: repeats
 * return the existing row, and a lost-insert race re-reads the winner via
 * {@link DataIntegrityViolationException} handling. No Java
 * synchronization; no HTTP/network inside any transaction.
 *
 * <p>User isolation is enforced through the assessment: every method
 * accepts an explicit {@code userId} and resolves ownership through
 * {@code assessment.user_id} first (notifications carry no duplicated
 * {@code user_id}, per the V3/V4/V7/V8 convention). Cross-user access
 * behaves as not-found and never reveals whether the row exists.
 */
@Service
public class NotificationService {

	private final NotificationRepository notificationRepository;
	private final ImpactAssessmentRepository assessmentRepository;
	private final RecommendationRepository recommendationRepository;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;

	public NotificationService(NotificationRepository notificationRepository,
			ImpactAssessmentRepository assessmentRepository,
			RecommendationRepository recommendationRepository,
			PlatformTransactionManager transactionManager,
			Clock clock) {
		if (notificationRepository == null || assessmentRepository == null
				|| recommendationRepository == null || transactionManager == null
				|| clock == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.notificationRepository = notificationRepository;
		this.assessmentRepository = assessmentRepository;
		this.recommendationRepository = recommendationRepository;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Returns the existing notification for the user's assessment, or emits
	 * one when the assessment's persisted recommendations contain actionable
	 * impact. Assessments whose only recommendation is
	 * {@code REC-NONE-REQUIRED} — or that have no recommendations yet —
	 * produce no notification and return {@link Optional#empty()}.
	 */
	public Optional<Notification> emitForAssessment(UUID userId, UUID assessmentId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (assessmentId == null) {
			throw new IllegalArgumentException("Assessment id must not be null");
		}
		ImpactAssessment assessment = ownedAssessment(userId, assessmentId);
		Optional<Notification> existing = notificationRepository.findByAssessment_Id(assessmentId);
		if (existing.isPresent()) {
			return existing;
		}
		List<Recommendation> rows = recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						assessmentId);
		boolean actionable = rows.stream().anyMatch(row ->
				!FrozenRecommendationRules.RULE_REC_NONE_REQUIRED.equals(row.getRuleId()));
		if (!actionable) {
			return Optional.empty();
		}
		try {
			return Optional.of(writeTransaction.execute(status -> notificationRepository
					.saveAndFlush(new Notification(assessment, clock.instant()))));
		}
		catch (DataIntegrityViolationException duplicate) {
			return notificationRepository.findByAssessment_Id(assessmentId)
					.map(Optional::of)
					.orElseThrow(() -> duplicate);
		}
	}

	/**
	 * Records the user reading one of their notifications. Idempotent: a
	 * second call is a no-op returning the unchanged row.
	 */
	public Notification markRead(UUID userId, UUID notificationId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (notificationId == null) {
			throw new IllegalArgumentException("Notification id must not be null");
		}
		Notification current = notificationRepository.findById(notificationId)
				.filter(notification -> notification.getAssessment().getUser().getId()
						.equals(userId))
				.orElseThrow(() -> new NotificationNotFoundException("Notification not found"));
		if (current.getReadAt() != null) {
			return current;
		}
		return writeTransaction.execute(status -> {
			Notification row = notificationRepository.findById(notificationId)
					.orElseThrow(() -> new NotificationNotFoundException("Notification not found"));
			row.markRead(clock.instant());
			return notificationRepository.save(row);
		});
	}

	/**
	 * Read-only, user-scoped access to the user's notifications, newest
	 * first.
	 */
	@Transactional(readOnly = true)
	public List<Notification> listNotifications(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return notificationRepository.findByAssessment_User_IdOrderByCreatedAtDesc(userId);
	}

	/**
	 * Read-only, user-scoped access to the user's unread notifications,
	 * newest first.
	 */
	@Transactional(readOnly = true)
	public List<Notification> listUnreadNotifications(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return notificationRepository
				.findByAssessment_User_IdAndReadAtIsNullOrderByCreatedAtDesc(userId);
	}

	/**
	 * Phase 10B-2B — controller-facing feed: delegates to
	 * {@link #listNotifications(UUID)} and maps to DTOs inside the same
	 * read transaction, so the lazy {@code newVersion} and
	 * {@code policy} associations resolve before the session closes.
	 * No ownership or ordering logic is duplicated here.
	 */
	@Transactional(readOnly = true)
	public List<NotificationResponse> listNotificationResponses(UUID userId) {
		return listNotifications(userId).stream()
				.map(NotificationResponse::from)
				.toList();
	}	/**
	 * Phase 10B-2B — controller-facing unread feed: delegates to
	 * {@link #listUnreadNotifications(UUID)} and maps to DTOs inside
	 * the same read transaction. No ownership or ordering logic is
	 * duplicated here.
	 */
	@Transactional(readOnly = true)
	public List<NotificationResponse> listUnreadNotificationResponses(UUID userId) {
		return listUnreadNotifications(userId).stream()
				.map(NotificationResponse::from)
				.toList();
	}

	/**
	 * Phase 13-B — paginated notification feeds (ADR-026). Same rows as
	 * {@link #listNotificationResponses(UUID)} and
	 * {@link #listUnreadNotificationResponses(UUID)}, windowed by
	 * {@code page}/{@code size} (defaults 0/20, maximum 100). The Sort
	 * carries the listing order (createdAt DESC, id DESC); the id
	 * tie-break is the approved ADR-026 refinement — the pre-13-B
	 * queries ordered by createdAt alone, which cannot paginate
	 * deterministically across equal timestamps. Mapping stays inside
	 * these read transactions; the existing unbounded methods stay for
	 * internal callers.
	 */
	@Transactional(readOnly = true)
	public List<NotificationResponse> listNotificationResponsesPaged(
			UUID userId, int page, int size) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		FeedPagination pagination = FeedPagination.of(page, size);
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		return notificationRepository.findByAssessment_User_Id(userId, pagination.pageRequest(sort))
				.stream()
				.map(NotificationResponse::from)
				.toList();
	}

	/**
	 * Phase 13-B — paginated unread feed; same contract as
	 * {@link #listNotificationResponsesPaged(UUID, int, int)} plus the
	 * unread predicate.
	 */
	@Transactional(readOnly = true)
	public List<NotificationResponse> listUnreadNotificationResponsesPaged(
			UUID userId, int page, int size) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		FeedPagination pagination = FeedPagination.of(page, size);
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		return notificationRepository
				.findByAssessment_User_IdAndReadAtIsNull(userId, pagination.pageRequest(sort))
				.stream()
				.map(NotificationResponse::from)
				.toList();
	}

	/**
	 * Phase 10B-2B — controller-facing mark-read: delegates to
	 * {@link #markRead(UUID, UUID)} inside this transaction and maps to
	 * the DTO before the session closes. Ownership validation,
	 * not-found behavior, and idempotency all come from the existing
	 * method; nothing is duplicated here.
	 */
	@Transactional
	public NotificationResponse markReadResponse(UUID userId, UUID notificationId) {
		return NotificationResponse.from(markRead(userId, notificationId));
	}

	private ImpactAssessment ownedAssessment(UUID userId, UUID assessmentId) {
		return assessmentRepository.findById(assessmentId)
				.filter(assessment -> assessment.getUser().getId().equals(userId))
				.orElseThrow(() -> new NotificationNotFoundException("Assessment not found"));
	}
}
