package com.soubhagya.policyimpactengine.impact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdown;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentBreakdownResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentDetailResponse;
import com.soubhagya.policyimpactengine.impact.web.dto.ImpactAssessmentSummaryResponse;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.user.EffectiveSensitivityResolver;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreference;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 2Q — user-triggered personalized assessment over persisted global
 * ChangeImpact rows.
 *
 * <p>Separate short transaction from the global policy observation
 * transaction: the observation path (version + changes + matches +
 * ChangeImpact) is never extended with per-user work. No HTTP/network
 * inside the transaction. Idempotent per (user, new version): repeats
 * return the existing row, backed by the database UNIQUE constraint.
 */
@Service
public class ImpactAssessmentService {

	private final UserRepository userRepository;
	private final PolicyVersionRepository versionRepository;
	private final PolicyChangeRecordRepository changeRepository;
	private final ChangeConceptMatchRepository matchRepository;
	private final ChangeImpactRepository impactRepository;
	private final PrivacyConceptRepository conceptRepository;
	private final UserPrivacyPreferenceRepository preferenceRepository;
	private final ImpactAssessmentRepository assessmentRepository;
	private final ImpactAssessmentBreakdownRepository breakdownRepository;
	private final EffectiveSensitivityResolver sensitivityResolver;
	private final PersonalizedImpactScoringEngine scoringEngine;
	private final TransactionTemplate writeTransaction;

	public ImpactAssessmentService(UserRepository userRepository,
			PolicyVersionRepository versionRepository,
			PolicyChangeRecordRepository changeRepository,
			ChangeConceptMatchRepository matchRepository,
			ChangeImpactRepository impactRepository,
			PrivacyConceptRepository conceptRepository,
			UserPrivacyPreferenceRepository preferenceRepository,
			ImpactAssessmentRepository assessmentRepository,
			ImpactAssessmentBreakdownRepository breakdownRepository,
			EffectiveSensitivityResolver sensitivityResolver,
			PersonalizedImpactScoringEngine scoringEngine,
			PlatformTransactionManager transactionManager) {
		if (userRepository == null || versionRepository == null || changeRepository == null
				|| matchRepository == null || impactRepository == null || conceptRepository == null
				|| preferenceRepository == null || assessmentRepository == null
				|| breakdownRepository == null || sensitivityResolver == null
				|| scoringEngine == null || transactionManager == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.userRepository = userRepository;
		this.versionRepository = versionRepository;
		this.changeRepository = changeRepository;
		this.matchRepository = matchRepository;
		this.impactRepository = impactRepository;
		this.conceptRepository = conceptRepository;
		this.preferenceRepository = preferenceRepository;
		this.assessmentRepository = assessmentRepository;
		this.breakdownRepository = breakdownRepository;
		this.sensitivityResolver = sensitivityResolver;
		this.scoringEngine = scoringEngine;
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Returns the existing assessment for (user, new version), or computes
	 * and persists it. A lost insert race re-reads the winner via the
	 * UNIQUE(user_id, new_version_id) constraint.
	 */
	public ImpactAssessment getOrCreateAssessment(UUID userId, UUID newVersionId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (newVersionId == null) {
			throw new IllegalArgumentException("New version id must not be null");
		}
		return assessmentRepository.findByUser_IdAndNewVersion_Id(userId, newVersionId)
				.orElseGet(() -> createAssessment(userId, newVersionId));
	}

	@Transactional(readOnly = true)
	public ImpactAssessment getAssessment(UUID userId, UUID newVersionId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (newVersionId == null) {
			throw new IllegalArgumentException("New version id must not be null");
		}
		return assessmentRepository.findByUser_IdAndNewVersion_Id(userId, newVersionId)
				.orElseThrow(() -> new IllegalArgumentException("Assessment not found"));
	}

	@Transactional(readOnly = true)
	public List<ImpactAssessment> listAssessments(UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return assessmentRepository.findByUser_IdOrderByCreatedAtDesc(userId);
	}

	/**
	 * Authenticated read API: the user's assessment summaries, newest
	 * first, mapped inside this read transaction so the lazy
	 * {@code newVersion}, {@code previousVersion}, and
	 * {@code newVersion.policy} associations resolve before the
	 * session closes. No scores are recomputed; the DTO carries the
	 * persisted summary facts only (no breakdown rows).
	 */
	@Transactional(readOnly = true)
	public List<ImpactAssessmentSummaryResponse> listAssessmentSummaries(
			UUID userId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		return assessmentRepository.findByUser_IdOrderByCreatedAtDescIdDesc(userId).stream()
				.map(ImpactAssessmentSummaryResponse::from)
				.toList();
	}

	/**
	 * Phase 13-B — paginated assessment summaries (ADR-026). Same rows
	 * and newest-first order as {@link #listAssessmentSummaries(UUID)},
	 * windowed by {@code page}/{@code size} (defaults 0/20, maximum
	 * 100). The Sort carries the exact listing order (createdAt DESC,
	 * id DESC). Mapping stays inside this read transaction; the
	 * existing unbounded method stays for internal callers.
	 *
	 * <p>Phase 13-F — reads through the fetch-join query so both
	 * version associations resolve in the page query (constant cost);
	 * rows, order, ownership, DTO shape, and transaction are unchanged.
	 */
	@Transactional(readOnly = true)
	public List<ImpactAssessmentSummaryResponse> listAssessmentSummariesPaged(
			UUID userId, int page, int size) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		FeedPagination pagination = FeedPagination.of(page, size);
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		return assessmentRepository.findPagedWithVersions(userId, pagination.pageRequest(sort)).stream()
				.map(ImpactAssessmentSummaryResponse::from)
				.toList();
	}

	/**
	 * Authenticated read API: one of the user's assessments plus its
	 * ordered breakdown rows, mapped inside this read transaction. A
	 * foreign id behaves as not-found and never reveals whether the
	 * row exists.
	 */
	@Transactional(readOnly = true)
	public ImpactAssessmentDetailResponse getAssessmentDetail(
			UUID userId, UUID assessmentId) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (assessmentId == null) {
			throw new IllegalArgumentException("Assessment id must not be null");
		}
		ImpactAssessment assessment = assessmentRepository.findByIdAndUser_Id(assessmentId, userId)
				.orElseThrow(() -> new ImpactAssessmentNotFoundException("Assessment not found"));
		List<ImpactAssessmentBreakdownResponse> breakdowns = breakdownRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescConceptCodeAsc(assessment.getId())
				.stream()
				.map(ImpactAssessmentBreakdownResponse::from)
				.toList();
		return ImpactAssessmentDetailResponse.from(
				assessment, breakdowns);
	}

	@Transactional(readOnly = true)
	public List<ImpactAssessmentBreakdown> listBreakdowns(UUID userId, UUID newVersionId) {
		ImpactAssessment assessment = getAssessment(userId, newVersionId);
		return breakdownRepository
				.findByAssessment_IdOrderByPersonalizedNormalizedDescConceptCodeAsc(assessment.getId());
	}

	private ImpactAssessment createAssessment(UUID userId, UUID newVersionId) {
		try {
			return writeTransaction.execute(status -> insertAssessment(userId, newVersionId));
		} catch (DataIntegrityViolationException duplicate) {
			return assessmentRepository.findByUser_IdAndNewVersion_Id(userId, newVersionId)
					.orElseThrow(() -> duplicate);
		}
	}

	private ImpactAssessment insertAssessment(UUID userId, UUID newVersionId) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
		PolicyVersion newVersion = versionRepository.findById(newVersionId)
				.orElseThrow(() -> new IllegalArgumentException("Version not found: " + newVersionId));
		if (newVersion.getVersionNumber() < 2) {
			throw new IllegalArgumentException("Version 1 has no transition to assess");
		}
		PolicyVersion previousVersion = versionRepository
				.findByPolicy_IdAndVersionNumber(
						newVersion.getPolicy().getId(), newVersion.getVersionNumber() - 1)
				.orElseThrow(() -> new IllegalArgumentException(
						"Previous version not found for: " + newVersionId));
		List<PolicyChangeRecord> changes = changeRepository
				.findByNewVersion_IdOrderByChangeOrderAsc(newVersion.getId());
		if (changes.isEmpty()) {
			throw new IllegalArgumentException("Version has no transition to assess: " + newVersionId);
		}
		for (PolicyChangeRecord change : changes) {
			if (!change.getPreviousVersion().getId().equals(previousVersion.getId())
					|| !change.getNewVersion().getId().equals(newVersion.getId())) {
				throw new IllegalStateException("Change does not belong to the assessed transition");
			}
		}
		List<UserPrivacyPreference> preferences = preferenceRepository
				.findByUser_IdOrderByConcept_CodeAsc(userId);
		List<PrivacyConcept> concepts = conceptRepository.findAll();
		Map<String, PrivacyConcept> conceptByCode = new LinkedHashMap<>();
		for (PrivacyConcept concept : concepts) {
			conceptByCode.put(concept.getCode(), concept);
		}
		Map<String, UserPrivacyPreference> preferenceByCode = new LinkedHashMap<>();
		for (UserPrivacyPreference preference : preferences) {
			preferenceByCode.put(preference.getConcept().getCode(), preference);
		}
		List<ChangeImpact> impacts = new ArrayList<>();
		for (PolicyChangeRecord change : changes) {
			List<ChangeConceptMatch> matches = matchRepository
					.findByChange_IdOrderByConcept_CodeAsc(change.getId());
			for (ChangeConceptMatch match : matches) {
				impacts.addAll(impactRepository.findByMatch_Id(match.getId()));
			}
		}
		impacts.sort(Comparator
				.comparing((ChangeImpact i) -> i.getMatch().getChange().getChangeOrder())
				.thenComparing(ChangeImpact::getConceptCode));
		List<PersonalizedItemScore> items = new ArrayList<>(impacts.size());
		List<Integer> sensitivities = new ArrayList<>(impacts.size());
		for (ChangeImpact impact : impacts) {
			PrivacyConcept concept = conceptByCode.get(impact.getConceptCode());
			if (concept == null) {
				throw new IllegalStateException(
						"Concept not found in vocabulary: " + impact.getConceptCode());
			}
			int effective = sensitivityResolver.resolve(
					preferenceByCode.get(concept.getCode()), concept);
			sensitivities.add(effective);
			items.add(scoringEngine.scoreItem(impact.getBaseScore(), effective));
		}
		int aggregate = 0;
		for (PersonalizedItemScore item : items) {
			aggregate = Math.max(aggregate, item.personalizedNormalized());
		}
		ImpactBand aggregateBand = ImpactBand.fromNormalizedScore(aggregate);
		ImpactAssessment assessment = assessmentRepository.saveAndFlush(new ImpactAssessment(user,
				newVersion, previousVersion, aggregate, aggregateBand,
				DeterministicPersonalizedImpactScoringEngine.PERSONALIZATION_RULES_VERSION));
		if (!items.isEmpty()) {
			List<ImpactAssessmentBreakdown> breakdowns = new ArrayList<>(impacts.size());
			for (int i = 0; i < impacts.size(); i++) {
				breakdowns.add(new ImpactAssessmentBreakdown(assessment, impacts.get(i),
						sensitivities.get(i), items.get(i).personalizedNormalized(),
						items.get(i).personalizedBand(),
						DeterministicPersonalizedImpactScoringEngine.PERSONALIZATION_RULES_VERSION));
			}
			breakdownRepository.saveAll(breakdowns);
			breakdownRepository.flush();
		}
		return assessment;
	}
}


