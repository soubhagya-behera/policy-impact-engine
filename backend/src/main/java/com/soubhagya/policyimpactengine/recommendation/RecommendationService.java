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
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;

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
 */
@Service
public class RecommendationService {

	private final ImpactAssessmentService assessmentService;
	private final ImpactAssessmentBreakdownRepository breakdownRepository;
	private final RecommendationRepository recommendationRepository;
	private final RecommendationEngine recommendationEngine;
	private final TransactionTemplate writeTransaction;

	public RecommendationService(ImpactAssessmentService assessmentService,
			ImpactAssessmentBreakdownRepository breakdownRepository,
			RecommendationRepository recommendationRepository,
			RecommendationEngine recommendationEngine,
			PlatformTransactionManager transactionManager) {
		if (assessmentService == null || breakdownRepository == null
				|| recommendationRepository == null || recommendationEngine == null
				|| transactionManager == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.assessmentService = assessmentService;
		this.breakdownRepository = breakdownRepository;
		this.recommendationRepository = recommendationRepository;
		this.recommendationEngine = recommendationEngine;
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Returns the existing recommendation set for (user, new version), or
	 * derives and persists it from the user's assessment. A lost-insert race
	 * re-reads the winner via the partial UNIQUE indexes.
	 */
	public List<Recommendation> getOrCreateRecommendations(UUID userId, UUID newVersionId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (newVersionId == null) {
			throw new IllegalArgumentException("New version id must not be null");
		}
		ImpactAssessment assessment = assessmentService.getOrCreateAssessment(userId, newVersionId);
		List<Recommendation> existing = findRecommendations(assessment.getId());
		if (!existing.isEmpty()) {
			return existing;
		}
		try {
			return writeTransaction.execute(status -> insertRecommendations(assessment));
		}
		catch (DataIntegrityViolationException duplicate) {
			return findRecommendations(assessment.getId());
		}
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
