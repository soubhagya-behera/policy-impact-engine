package com.soubhagya.policyimpactengine.policy.application;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.web.dto.ChangeRecordResponse;
import com.soubhagya.policyimpactengine.policy.web.dto.VersionDiffResponse;

/**
 * Phase 14-B/2 — read-only change-history queries for the authenticated
 * REST surface (see ARCHITECTURE.md §28).
 *
 * <p>Every read is owner-scoped at the repository level through
 * {@code change.newVersion.policy.owner}: foreign or unknown policies
 * yield an empty change page, and foreign, unknown, or mismatched
 * versions behave as not-found. Only persisted
 * {@link PolicyChangeRecord} rows are ever returned; the diff engine is
 * never invoked and no transition is ever recomputed or concatenated.
 * The caller's user id always comes from the authenticated principal
 * via the controller; this service never touches the security context
 * and performs no writes.
 */
@Service
public class PolicyChangeReadService {

	private final PolicyChangeRecordRepository changes;
	private final PolicyVersionRepository versions;

	public PolicyChangeReadService(PolicyChangeRecordRepository changes,
			PolicyVersionRepository versions) {
		if (changes == null) {
			throw new IllegalArgumentException("PolicyChangeRecordRepository must not be null");
		}
		if (versions == null) {
			throw new IllegalArgumentException("PolicyVersionRepository must not be null");
		}
		this.changes = changes;
		this.versions = versions;
	}

	/**
	 * Returns one window of the user's persisted change history for a
	 * policy in transition order (successor version number ascending,
	 * then {@code changeOrder} ascending). A foreign or unknown policy,
	 * or a policy with no change rows (for example V1-only), yields an
	 * empty list. Windowed by {@code page}/{@code size} (defaults 0/20,
	 * maximum 100); invalid values are rejected, never clamped.
	 */
	@Transactional(readOnly = true)
	public List<ChangeRecordResponse> list(UUID userId, UUID policyId, int page, int size) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		FeedPagination pagination = FeedPagination.of(page, size);
		return changes
				.findByNewVersion_Policy_IdAndNewVersion_Policy_Owner_IdOrderByNewVersion_VersionNumberAscChangeOrderAsc(
						policyId, userId, pagination.pageRequest(Sort.unsorted()))
				.stream()
				.map(ChangeRecordResponse::from)
				.toList();
	}

	/**
	 * Returns the persisted changes for one adjacent version transition
	 * ({@code to == from + 1}). Both versions are resolved owner-scoped
	 * by their 1-based version numbers; unknown, foreign, or
	 * policy-mismatched versions behave as not-found. A non-adjacent
	 * range is rejected, as are version numbers below 1. Every returned
	 * row's persisted predecessor must be the requested predecessor;
	 * otherwise the transition behaves as not-found.
	 */
	@Transactional(readOnly = true)
	public VersionDiffResponse diff(UUID userId, UUID policyId, int from, int to) {
		if (userId == null) {
			throw new IllegalArgumentException("User id must not be null");
		}
		if (policyId == null) {
			throw new IllegalArgumentException("Policy id must not be null");
		}
		if (from < 1) {
			throw new IllegalArgumentException("from version must be >= 1, got: " + from);
		}
		if (to < 1) {
			throw new IllegalArgumentException("to version must be >= 1, got: " + to);
		}
		if (to - from != 1) {
			throw new IllegalArgumentException(
					"Only adjacent versions can be diffed: expected to == from + 1, got from="
							+ from + " to=" + to);
		}
		PolicyVersion fromVersion = versions
				.findByPolicy_IdAndVersionNumberAndPolicy_Owner_Id(policyId, from, userId)
				.orElseThrow(() -> new NoSuchElementException(
						"Policy version " + from + " not found for policy " + policyId));
		PolicyVersion toVersion = versions
				.findByPolicy_IdAndVersionNumberAndPolicy_Owner_Id(policyId, to, userId)
				.orElseThrow(() -> new NoSuchElementException(
						"Policy version " + to + " not found for policy " + policyId));
		List<ChangeRecordResponse> rows = changes
				.findByNewVersion_IdAndNewVersion_Policy_Owner_IdOrderByChangeOrderAsc(
						toVersion.getId(), userId)
				.stream()
				.peek(row -> requirePredecessor(row, fromVersion, policyId))
				.map(ChangeRecordResponse::from)
				.toList();
		return new VersionDiffResponse(
				policyId, from, to, fromVersion.getId(), toVersion.getId(), rows);
	}

	/**
	 * Verifies one persisted row belongs to the requested transition:
	 * its persisted predecessor must be the requested predecessor
	 * version. A row pointing anywhere else means the requested
	 * successor is not linked to the requested predecessor, so the
	 * transition behaves as not-found.
	 */
	private static void requirePredecessor(PolicyChangeRecord row, PolicyVersion fromVersion,
			UUID policyId) {
		if (!row.getPreviousVersion().getId().equals(fromVersion.getId())) {
			throw new NoSuchElementException("Policy version " + fromVersion.getVersionNumber()
					+ " not found for policy " + policyId);
		}
	}
}
