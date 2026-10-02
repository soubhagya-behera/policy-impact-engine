package com.soubhagya.policyimpactengine.policy.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.monitoring.domain.PolicyFetchAttempt;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;

/**
 * Phase 17-A — compact current-state view of one owned policy (see
 * DECISIONS.md ADR-036): the policy row plus the three latest persisted
 * facts (latest version, latest check, latest impact).
 *
 * <p>Every value is read from a persisted scalar and nothing is derived,
 * recomputed, or invented. The three nested blocks are {@code null} when
 * the corresponding row does not exist — a freshly registered policy that
 * has never been checked reports {@code null} rather than a zero score, an
 * empty band, or an epoch timestamp, so "never observed" is never confused
 * with "observed and empty". {@code nextCheckAt} is the exact stored
 * scheduling timestamp, never calculated or advanced here.
 *
 * <p>Deliberately absent: {@code errorMessage} (owned by the check-history
 * feed), {@code normalized_content}, {@code personalizationRulesVersion},
 * assessment breakdown rows, recommendations, notifications, changes, audit
 * data, credentials, and secrets. No JPA entity, entity association, or
 * interface projection is exposed anywhere in this shape, and mapping runs
 * inside the read transaction so no lazy association is initialized.
 */
public record PolicyOverviewResponse(

		UUID policyId,
		String name,
		String url,
		String status,
		Instant createdAt,
		Instant updatedAt,
		Instant nextCheckAt,
		LatestVersion latestVersion,
		LatestCheck latestCheck,
		LatestImpact latestImpact

) {

	/**
	 * The highest-numbered persisted version of this policy. Identity,
	 * version number, content hash, and observation time only — never the
	 * normalized document body.
	 */
	public record LatestVersion(

			int versionNumber,
			String contentHash,
			Instant observedAt

	) {

		public static LatestVersion from(PolicyVersion version) {
			if (version == null) {
				throw new IllegalArgumentException("Version must not be null");
			}
			return new LatestVersion(
					version.getVersionNumber(),
					version.getContentHash(),
					version.getObservedAt());
		}
	}

	/**
	 * The newest persisted check attempt for this policy
	 * ({@code startedAt DESC, id DESC}). Rendered verbatim: every lifecycle
	 * state and both failure kinds pass through unchanged, {@code failureKind}
	 * is non-null only on {@code FAILED}, and {@code durationMs}/
	 * {@code completedAt} stay null while the attempt is live
	 * ({@code PENDING}/{@code IN_PROGRESS}).
	 */
	public record LatestCheck(

			UUID id,
			String trigger,
			int attemptNumber,
			String status,
			String failureKind,
			Integer httpStatus,
			Long bytesFetched,
			Long durationMs,
			Instant startedAt,
			Instant completedAt

	) {

		public static LatestCheck from(PolicyFetchAttempt attempt) {
			if (attempt == null) {
				throw new IllegalArgumentException("Attempt must not be null");
			}
			return new LatestCheck(
					attempt.getId(),
					attempt.getTrigger().name(),
					attempt.getAttemptNumber(),
					attempt.getStatus().name(),
					attempt.getFailureKind() == null ? null : attempt.getFailureKind().name(),
					attempt.getHttpStatus(),
					attempt.getBytesFetched(),
					attempt.getDurationMs(),
					attempt.getStartedAt(),
					attempt.getCompletedAt());
		}
	}

	/**
	 * The newest persisted assessment belonging to this policy and its
	 * authenticated owner: the owner's own score, band, and assessment
	 * time exactly as stored. Scoring is never recomputed and an
	 * assessment is never created by a read.
	 */
	public record LatestImpact(

			int aggregateScore,
			String band,
			Instant assessedAt

	) {

		public static LatestImpact from(ImpactAssessment assessment) {
			if (assessment == null) {
				throw new IllegalArgumentException("Assessment must not be null");
			}
			return new LatestImpact(
					assessment.getAggregateScore(),
					assessment.getAggregateBand().name(),
					assessment.getCreatedAt());
		}
	}

	/**
	 * Assembles the overview from the already-owned policy row and the
	 * three nullable latest-fact rows. Each absent fact becomes a
	 * {@code null} block rather than a fabricated value.
	 */
	public static PolicyOverviewResponse from(Policy policy, PolicyVersion latestVersion,
			PolicyFetchAttempt latestCheck, ImpactAssessment latestImpact) {
		if (policy == null) {
			throw new IllegalArgumentException("Policy must not be null");
		}
		return new PolicyOverviewResponse(
				policy.getId(),
				policy.getName(),
				policy.getUrl(),
				policy.getStatus().name(),
				policy.getCreatedAt(),
				policy.getUpdatedAt(),
				policy.getNextCheckAt(),
				latestVersion == null ? null : LatestVersion.from(latestVersion),
				latestCheck == null ? null : LatestCheck.from(latestCheck),
				latestImpact == null ? null : LatestImpact.from(latestImpact));
	}

}