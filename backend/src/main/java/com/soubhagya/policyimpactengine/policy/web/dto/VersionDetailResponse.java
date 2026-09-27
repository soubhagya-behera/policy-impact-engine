package com.soubhagya.policyimpactengine.policy.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;

/**
 * Phase 14-B/1 — single version snapshot for the version-detail
 * endpoint. The summary fields plus the canonical normalized
 * content observed at this version; no other entity state.
 */
public record VersionDetailResponse(

		UUID id,
		UUID policyId,
		int versionNumber,
		String contentHash,
		Instant observedAt,
		String normalizedContent

) {

	public static VersionDetailResponse from(PolicyVersion version) {
		return new VersionDetailResponse(
				version.getId(),
				version.getPolicy().getId(),
				version.getVersionNumber(),
				version.getContentHash(),
				version.getObservedAt(),
				version.getNormalizedContent());
	}
}
