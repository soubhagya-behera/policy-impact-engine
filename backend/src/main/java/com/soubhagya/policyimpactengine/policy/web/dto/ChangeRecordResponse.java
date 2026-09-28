package com.soubhagya.policyimpactengine.policy.web.dto;

import java.util.UUID;

import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;

/**
 * Phase 14-B/2 — one persisted change row of the owner-scoped
 * change-history feed. Carries only persisted facts: the row identity,
 * the change type, the old/new texts, the zero-based document position,
 * the successor version number, and the successor version id. No other
 * entity state is exposed.
 */
public record ChangeRecordResponse(

		UUID id,
		PolicyChangeType changeType,
		String oldText,
		String newText,
		int changeOrder,
		int versionNumber,
		UUID newVersionId

) {

	public static ChangeRecordResponse from(PolicyChangeRecord change) {
		return new ChangeRecordResponse(
				change.getId(),
				change.getChangeType(),
				change.getOldText(),
				change.getNewText(),
				change.getChangeOrder(),
				change.getNewVersion().getVersionNumber(),
				change.getNewVersion().getId());
	}
}
