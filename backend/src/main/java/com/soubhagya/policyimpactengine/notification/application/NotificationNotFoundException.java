package com.soubhagya.policyimpactengine.notification.application;

import java.util.NoSuchElementException;

/**
 * Phase 10B-1 — not-found signal for user-scoped notification reads.
 *
 * <p>Extends {@link NoSuchElementException} (not
 * {@link IllegalArgumentException}) so missing or foreign assessments
 * and notifications map to HTTP 404 rather than 400 when the REST feed
 * lands (Phase 10B-2). Messages are unchanged from Phase 10A so
 * existing message assertions keep passing.
 */
public class NotificationNotFoundException extends NoSuchElementException {

	public NotificationNotFoundException(String message) {
		super(message);
	}
}
