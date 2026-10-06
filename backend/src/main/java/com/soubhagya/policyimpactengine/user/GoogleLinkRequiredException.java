package com.soubhagya.policyimpactengine.user;

/**
 * Phase 18-B — Google account-linking conflict (see DECISIONS.md ADR-037).
 *
 * <p>An anonymous Google login presented a verified email that already
 * belongs to a local account with a different (or absent) Google subject.
 * Never merged automatically: the caller must prove ownership (a later
 * explicit linking phase) before any {@code google_sub} is written.
 * Maps to HTTP 409 through the existing global handler.
 */
public class GoogleLinkRequiredException extends RuntimeException {

	public GoogleLinkRequiredException(String message) {
		super(message);
	}
}
