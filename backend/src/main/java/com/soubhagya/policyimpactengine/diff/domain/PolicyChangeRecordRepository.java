package com.soubhagya.policyimpactengine.diff.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link PolicyChangeRecord}.
 *
 * <p>Only the minimum operations required by Phase 2K are exposed:
 * standard save support from {@link JpaRepository} (used with
 * {@code saveAll} + {@code flush} inside the single version-plus-changes
 * transaction) and deterministic document-order retrieval of the changes
 * for one version transition. No update, custom delete, or diff logic
 * lives here.
 *
 * <p>Phase 14-B/2 adds the owner-scoped reads backing the change-history
 * REST surface: ownership chains through
 * {@code change.newVersion.policy.owner}, so foreign or unknown rows
 * behave as empty results. Windowed listings take a
 * caller-supplied {@link Pageable} for the limit/offset window only;
 * the feed ordering (new-version number ascending, then document
 * position ascending) is fixed in the query name.
 */
public interface PolicyChangeRecordRepository extends JpaRepository<PolicyChangeRecord, UUID> {

	/**
	 * Returns the persisted changes whose new version is the given version,
	 * in deterministic document order.
	 */
	List<PolicyChangeRecord> findByNewVersion_IdOrderByChangeOrderAsc(UUID newVersionId);

	/**
	 * Phase 14-B/2 — windowed owner-scoped change listing for the
	 * changes feed. A foreign or unknown policy behaves as an empty
	 * result. Ordering is new-version number ascending, then
	 * {@code changeOrder} ascending.
	 */
	List<PolicyChangeRecord> findByNewVersion_Policy_IdAndNewVersion_Policy_Owner_IdOrderByNewVersion_VersionNumberAscChangeOrderAsc(
			UUID policyId, UUID ownerId, Pageable pageable);

	/**
	 * Phase 14-B/2 — single-transition lookup scoped to the owning user
	 * for the adjacent-version diff endpoint. A foreign or unknown new
	 * version behaves as an empty result; callers additionally verify
	 * the rows belong to the requested predecessor.
	 */
	List<PolicyChangeRecord> findByNewVersion_IdAndNewVersion_Policy_Owner_IdOrderByChangeOrderAsc(
			UUID newVersionId, UUID ownerId);

}
