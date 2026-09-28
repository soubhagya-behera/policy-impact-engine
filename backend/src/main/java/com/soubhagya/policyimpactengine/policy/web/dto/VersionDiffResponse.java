package com.soubhagya.policyimpactengine.policy.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * Phase 14-B/2 — persisted changes for one adjacent version transition.
 * The {@code changes} list carries exactly the persisted
 * {@link ChangeRecordResponse} rows for the requested transition in
 * {@code changeOrder} order; the diff is never recomputed and never
 * spans multiple transitions.
 */
public record VersionDiffResponse(

		UUID policyId,
		int fromVersion,
		int toVersion,
		UUID fromVersionId,
		UUID toVersionId,
		List<ChangeRecordResponse> changes

) {
}
