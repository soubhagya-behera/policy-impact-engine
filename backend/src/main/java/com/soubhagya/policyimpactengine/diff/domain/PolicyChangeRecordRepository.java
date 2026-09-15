package com.soubhagya.policyimpactengine.diff.domain;

import java.util.List;
import java.util.UUID;

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
 */
public interface PolicyChangeRecordRepository extends JpaRepository<PolicyChangeRecord, UUID> {

	/**
	 * Returns the persisted changes whose new version is the given version,
	 * in deterministic document order.
	 */
	List<PolicyChangeRecord> findByNewVersion_IdOrderByChangeOrderAsc(UUID newVersionId);

}
