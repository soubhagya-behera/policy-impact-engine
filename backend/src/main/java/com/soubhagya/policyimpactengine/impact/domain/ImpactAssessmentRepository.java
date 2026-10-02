package com.soubhagya.policyimpactengine.impact.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Phase 2Q — persistence for {@link ImpactAssessment}.
 *
 * <p>User-scoped queries only; no unrestricted listing.
 */
public interface ImpactAssessmentRepository extends JpaRepository<ImpactAssessment, UUID> {

	Optional<ImpactAssessment> findByUser_IdAndNewVersion_Id(UUID userId, UUID newVersionId);

	List<ImpactAssessment> findByUser_IdOrderByCreatedAtDesc(UUID userId);

	/**
	 * User-scoped identity lookup for the authenticated read API.
	 * A foreign id behaves as not-found (callers map the empty case
	 * to 404 and never reveal whether the row exists).
	 */
	Optional<ImpactAssessment> findByIdAndUser_Id(UUID id, UUID userId);

	/**
	 * User-scoped listing for the authenticated read API, newest
	 * first with the id as the final tie-break so the order is
	 * deterministic even when two rows share a timestamp.
	 */
	List<ImpactAssessment> findByUser_IdOrderByCreatedAtDescIdDesc(UUID userId);

	/**
	 * Phase 13-B — paginated user-scoped listing. No ordering is
	 * embedded in the name: the caller supplies the exact deterministic
	 * Sort (createdAt DESC, id DESC) through the Pageable. Returns a
	 * bare list (limit/offset only, no count query).
	 */
	List<ImpactAssessment> findByUser_Id(UUID userId, Pageable pageable);

	/**
	 * Phase 13-F — paginated user-scoped listing with both version
	 * associations fetched in the page query. Same rows, ownership,
	 * and caller-supplied Sort as {@link #findByUser_Id(UUID, Pageable)};
	 * the DTO reads {@code newVersion} and {@code previousVersion} for
	 * every row, so fetching exactly those two to-one associations
	 * turns the 1+2N lazy pattern into a constant page cost. Inner
	 * joins are correct: {@code previous_version_id} is NOT NULL in
	 * V7, {@code optional = false} on the mapping, the constructor
	 * rejects null, and creation requires version &gt;= 2 with a
	 * resolved predecessor. Neither {@code nv.policy} nor {@code user}
	 * is fetched: the DTO needs only the policy id (served from the
	 * uninitialized proxy) and never reads the user.
	 */
	@Query("SELECT a FROM ImpactAssessment a "
			+ "JOIN FETCH a.newVersion nv "
			+ "JOIN FETCH a.previousVersion pv "
			+ "WHERE a.user.id = :userId")
	List<ImpactAssessment> findPagedWithVersions(@Param("userId") UUID userId, Pageable pageable);

	/**
	 * Phase 14-B/4 — total persisted assessments for one user.
	 */
	long countByUser_Id(UUID userId);

	/**
	 * Phase 14-B/4 — per-band assessment counts for one user from a
	 * single GROUP BY query. Each row is
	 * {@code Object[]{ImpactBand, Long}}; callers map the band to its
	 * enum name. No assessment entities are loaded.
	 */
	@Query("SELECT a.aggregateBand, COUNT(a) FROM ImpactAssessment a "
			+ "WHERE a.user.id = :userId GROUP BY a.aggregateBand")
	List<Object[]> countByBandForUser(@Param("userId") UUID userId);

	/**
	 * Phase 14-B/4 — maximum persisted aggregate score for one user;
	 * 0 when the user has no assessments.
	 */
	@Query("SELECT COALESCE(MAX(a.aggregateScore), 0) FROM ImpactAssessment a "
			+ "WHERE a.user.id = :userId")
	int maxAggregateScoreForUser(@Param("userId") UUID userId);

	/**
	 * Phase 14-B/4 — newest assessment for one user in the existing
	 * newest-first order (createdAt DESC, id DESC) as a constant
	 * single-row query for the impact summary.
	 */
	Optional<ImpactAssessment> findFirstByUser_IdOrderByCreatedAtDescIdDesc(UUID userId);

	/**
	 * Phase 17-A — newest assessment for one policy scoped to its
	 * authenticated owner (see DECISIONS.md ADR-036), in the existing
	 * 14-B/4 newest-first order. Both conjuncts are load-bearing:
	 * {@code ImpactAssessment} has no policy column of its own (the link
	 * runs through {@code newVersion.policy}) and no owner-independent
	 * notion of "the" assessment, so a user-only filter would pull in
	 * another policy's assessment and a policy-only filter would expose
	 * another user's personalized score. No such query existed before the
	 * policy-overview projection, which reads only the row's own scalars
	 * and therefore never initializes {@code newVersion},
	 * {@code previousVersion}, or {@code user}.
	 */
	Optional<ImpactAssessment> findFirstByUser_IdAndNewVersion_Policy_IdOrderByCreatedAtDescIdDesc(
			UUID userId, UUID policyId);
}
