package com.soubhagya.policyimpactengine.audit.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.policyimpactengine.audit.domain.AuditEvent;

/**
 * Phase 11D — authenticated audit feed item: the persisted safe
 * facts of one audit event.
 *
 * <p>Exactly eight fields. No actor identity (the feed is
 * self-scoped), no user entity, credentials, tokens, secrets, email,
 * policy content, or internal relationships are exposed. {@code
 * eventType} renders as the enum name, matching the existing
 * assessment/notification response conventions. {@code metadata} is
 * the persisted TEXT verbatim — never parsed or re-serialized — and
 * {@code null} stays {@code null} (distinct from {@code "{}"}).
 * Hashes are returned exactly as persisted, never recomputed.
 *
 * <p>Mapping must execute inside the service transaction that loaded
 * the event. Only scalar getters are read, so the lazy actor
 * association is never initialized.
 */
public record AuditEventResponse(

		UUID id,
		Instant occurredAt,
		String eventType,
		String resourceType,
		UUID resourceId,
		String metadata,
		String prevHash,
		String eventHash

) {

	public static AuditEventResponse from(AuditEvent event) {
		if (event == null) {
			throw new IllegalArgumentException("Audit event must not be null");
		}
		return new AuditEventResponse(
				event.getId(),
				event.getOccurredAt(),
				event.getEventType().name(),
				event.getResourceType(),
				event.getResourceId(),
				event.getMetadata(),
				event.getPrevHash(),
				event.getEventHash());
	}

}
