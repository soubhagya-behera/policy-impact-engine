package com.soubhagya.policyimpactengine.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Phase 11A — pure unit tests for {@link AuditEvent} construction
 * rules: required fields, hash shape, genesis shape, and value
 * normalization. No Spring context, no database. Append-only behavior
 * holds by architecture (no setters, no mutation methods, no
 * update/delete operations anywhere in the slice).
 */
class AuditEventTest {

	private static final Instant AT = Instant.parse("2026-09-18T10:00:00Z");
	private static final String HASH =
			"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void genesisShapeRoundTrips() {
		AuditEvent event = new AuditEvent(null, AuditEventType.AUTH_USER_REGISTERED, null,
				null, AT, null, null, HASH);

		assertThat(event.getId()).isNull();
		assertThat(event.getActorUser()).isNull();
		assertThat(event.getEventType()).isEqualTo(AuditEventType.AUTH_USER_REGISTERED);
		assertThat(event.getResourceType()).isNull();
		assertThat(event.getResourceId()).isNull();
		assertThat(event.getOccurredAt()).isEqualTo(AT);
		assertThat(event.getMetadata()).isNull();
		assertThat(event.getPrevHash()).isNull();
		assertThat(event.getEventHash()).isEqualTo(HASH);
	}

	@Test
	void resourceTypeIsTrimmed() {
		AuditEvent event = new AuditEvent(null, AuditEventType.POLICY_REGISTERED, "  POLICY  ",
				UUID.randomUUID(), AT, "{\"a\":1}", HASH, HASH);

		assertThat(event.getResourceType()).isEqualTo("POLICY");
	}

	@Test
	void catalogHoldsExactlyTheFirstWaveCodes() {
		assertThat(AuditEventType.values()).containsExactly(
				AuditEventType.AUTH_USER_REGISTERED,
				AuditEventType.AUTH_LOGIN_SUCCEEDED,
				AuditEventType.POLICY_REGISTERED,
				AuditEventType.POLICY_OWNER_ASSIGNED,
				AuditEventType.PRIVACY_PREFERENCE_UPSERTED,
				AuditEventType.PRIVACY_PREFERENCE_DELETED);
	}

	@Test
	void invalidConstructionIsRejected() {
		assertThatThrownBy(() -> new AuditEvent(null, null, null, null, AT, null, null, HASH))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuditEvent(null, AuditEventType.AUTH_USER_REGISTERED,
				null, null, null, null, null, HASH))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuditEvent(null, AuditEventType.AUTH_USER_REGISTERED,
				"   ", null, AT, null, null, HASH))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuditEvent(null, AuditEventType.AUTH_USER_REGISTERED,
				null, null, AT, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuditEvent(null, AuditEventType.AUTH_USER_REGISTERED,
				null, null, AT, null, null, "abc"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuditEvent(null, AuditEventType.AUTH_USER_REGISTERED,
				null, null, AT, null, "not-hex-at-all!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!",
				HASH))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuditEvent(null, AuditEventType.AUTH_USER_REGISTERED,
				null, null, AT, null, null, HASH.toUpperCase()))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
