package com.soubhagya.policyimpactengine.policy.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;

/**
 * Phase 14-B/1 — one row of the owner-scoped version-history feed.
 * Carries the version identity, its parent policy, the 1-based
 * sequence number, the content hash, and the observation time only;
 * never the full normalized content (see
 * {@link VersionDetailResponse}).
 */
public record VersionSummaryResponse(

		UUID id,
		UUID policyId,
		int versionNumber,
		String contentHash,
		Instant observedAt

) {

	public static VersionSummaryResponse from(PolicyVersion version) {
		return new VersionSummaryResponse(
				version.getId(),
				version.getPolicy().getId(),
				version.getVersionNumber(),
				version.getContentHash(),
				version.getObservedAt());
	}
}
