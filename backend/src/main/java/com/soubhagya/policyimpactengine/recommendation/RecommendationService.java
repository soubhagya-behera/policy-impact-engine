package com.soubhagya.policyimpactengine.recommendation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.notification.application.NotificationService;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.recommendation.web.dto.RecommendationDetailResponse;
import com.soubhagya.policyimpactengine.recommendation.web.dto.RecommendationSummaryResponse;

/**
 * Phase 2R — user-triggered recommendation derivation over the user's
 * personalized {@link ImpactAssessment}.
 *
 * <p>Separate short transactions, mirroring Phase 2Q: the assessment is
 * obtained via {@link ImpactAssessmentService#getOrCreateAssessment} (its own
 * short transaction), then recommendations are computed and persisted in one
 * short write transaction with no HTTP/network inside. Idempotent per
 * (user, new version): repeats return the existing, unchanged recommendation
 * set, backed by the partial UNIQUE indexes — a lost-insert race re-reads the
 * winner via {@link DataIntegrityViolationException} handling.
 *
 * <p>User isolation is enforced through the assessment: the service boundary
 * accepts an explicit {@code userId} and resolves the user's own assessment
 * for the version first; recommendation rows carry no duplicated
 * {@code user_id} because ownership is derived through
 * {@code assessment_id} → {@code impact_assessment.user_id} (V3/V4/V7
 * no-duplicated-owner-FK convention).
 *
 * <p>Recommendations are append-only: nothing is mutated or deleted. The
 * current/pending recommendation set for a user is the set attached to the
 * user's latest assessment (deliberate Phase 2R interpretation of the older
 * "replace pending set" wording).
 *
 * <p>The pure engine runs inside the write transaction but performs no I/O;
 * an engine failure or a flush failure rolls the transaction back and leaves
 * the previously committed assessment intact.
 *
 * <p>Phase 10A notification edge: after recommendations commit,
 * {@link NotificationService#emitForAssessment} records the in-app
 * notification for assessments with actionable impact. The edge is narrow
 * and one-directional (recommendation → notification; notification never
 * calls back) and runs in its own short transaction: an emission failure
 * propagates without rolling back the committed recommendations, and a
 * later call heals the missing notification idempotently.
 */
@Service
public class RecommendationService {

	private final ImpactAssessmentService assessmentService;
	private final ImpactAssessmentBreakdownRepository breakdownRepository;
	private final RecommendationRepository recommendationRepository;
	private final RecommendationEngine recommendationEngine;
	private final NotificationService notificationService;
	private final TransactionTemplate writeTransaction;

	public RecommendationService(ImpactAssessmentService assessmentService,
			ImpactAssessmentBreakdownRepository breakdownRepository,
			RecommendationRepository recommendationRepository,
			RecommendationEngine recommendationEngine,
			NotificationService notificationService,
			PlatformTransactionManager transactionManager) {
		if (assessmentService == null || breakdownRepository == null
				|| recommendationRepository == null || recommendationEngine == null
				|| notificationService == null || transactionManager == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.assessmentService = assessmentService;
		this.breakdownRepository = breakdownRepository;
		this.recommendationRepository = recommendationRepository;
		this.recommendationEngine = recommendationEngine;
		this.notificationService = notificationService;
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Returns the existing recommendation set for (user, new version), or
	 * derives and persists it from the user's assessment. A lost-insert race
	 * re-reads the winner via the partial UNIQUE indexes.
	 *
	 * <p>Phase 10A: after the set is resolved (existing, created, or
	 * re-read), the notification edge emits the in-app notification for
	 * assessments with actionable impact. Emission runs in its own short
	 * transaction after this method's work commits, so an emission failure
	 * propagates with the recommendations already persisted — and a later
	 * call heals the missing notification idempotently.
	 */
	public List<Recommendation> getOrCreateRecommendations(UUID userId, UUID newVersionId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (newVersionId == null) {
			throw new IllegalArgumentException("New version id must not be null");
		}
		ImpactAssessment assessment = assessmentService.getOrCreateAssessment(userId, newVersionId);
		List<Recommendation> recommendations = findRecommendations(assessment.getId());
		if (recommendations.isEmpty()) {
			try {
				recommendations = writeTransaction.execute(status -> insertRecommendations(assessment));
			}
			catch (DataIntegrityViolationException duplicate) {
				recommendations = findRecommendations(assessment.getId());
			}
		}
		notificationService.emitForAssessment(userId, assessment.getId());
		return recommendations;
	}

	/**
	 * Read-only, user-scoped access to the recommendation set of one
	 * assessment. Throws when the user has no assessment for the version.
	 */
	@Transactional(readOnly = true)
	public List<Recommendation> getRecommendations(UUID userId, UUID newVersionId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (newVersionId == null) {
			throw new IllegalArgumentException("New version id must not be null");
		}
		ImpactAssessment assessment = assessmentService.getAssessment(userId, newVersionId);
		return findRecommendations(assessment.getId());
	}

	/**
	 * Authenticated read API: the user's recommendations across all of
	 * their assessments, newest first, mapped inside this read
	 * transaction. Ownership is derived through the assessment
	 * (Recommendation → ImpactAssessment → User) at the repository
	 * level; no rows are loaded and filtered in Java. A foreign id
	 * behaves as not-found and never reveals whether the row exists.
	 */
	@Transactional(readOnly = true)
	public List<RecommendationSummaryResponse> listRecommendationResponses(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return recommendationRepository.findByAssessment_User_IdOrderByCreatedAtDescIdDesc(userId)
				.stream()
				.map(RecommendationSummaryResponse::from)
				.toList();
	}

	/**
	 * Authenticated read API: one of the user's recommendations with
	 * its assessment navigation context, mapped inside this read
	 * transaction so the lazy {@code assessment},
	 * {@code newVersion}, and {@code newVersion.policy} associations
	 * resolve before the session closes.
	 */
	@Transactional(readOnly = true)
	public RecommendationDetailResponse getRecommendationDetail(UUID userId, UUID recommendationId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (recommendationId == null) {
			throw new IllegalArgumentException("Recommendation id must not be null");
		}
		return recommendationRepository.findByIdAndAssessment_User_Id(recommendationId, userId)
				.map(RecommendationDetailResponse::from)
				.orElseThrow(() -> new RecommendationNotFoundException("Recommendation not found"));
	}

	private List<Recommendation> insertRecommendations(ImpactAssessment assessment) {
		List<ImpactAssessmentBreakdown> breakdowns = breakdownRepository
				.findByAssessment_IdOrderByConceptCodeAsc(assessment.getId());
		List<ConceptItemScore> items = new ArrayList<>(breakdowns.size());
		for (ImpactAssessmentBreakdown breakdown : breakdowns) {
			items.add(new ConceptItemScore(breakdown.getConceptCode(), breakdown.getChangeType(),
					breakdown.getPersonalizedNormalized(), breakdown.getPersonalizedBand()));
		}
		RecommendationResult result = recommendationEngine.recommend(new RecommendationInput(
				items, assessment.getAggregateScore(), assessment.getAggregateBand()));
		if (result.recommendations().isEmpty()) {
			return List.of();
		}
		List<Recommendation> rows = new ArrayList<>(result.recommendations().size());
		for (RecommendationOutcome outcome : result.recommendations()) {
			rows.add(new Recommendation(assessment, outcome.ruleId(), outcome.ruleOrder(),
					outcome.actionKind(), outcome.conceptCode(), outcome.personalizedNormalized(),
					outcome.personalizedBand(), result.recommendationRulesVersion()));
		}
		recommendationRepository.saveAll(rows);
		recommendationRepository.flush();
		return findRecommendations(assessment.getId());
	}

	private List<Recommendation> findRecommendations(UUID assessmentId) {
		return recommendationRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescRuleOrderAscConceptCodeAsc(
						assessmentId);
	}
}
