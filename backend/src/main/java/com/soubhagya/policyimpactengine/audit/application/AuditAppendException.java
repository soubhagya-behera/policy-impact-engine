package com.soubhagya.policyimpactengine.audit.application;

/**
 * Phase 11A — failure signal for audit appends.
 *
 * <p>Thrown when appending exhausts the bounded predecessor-collision
 * retry budget. Carries the last database failure as the cause. Never
 * rolls back business state: audit writes run in their own short
 * transaction after the business commit (see ADR-022).
 */
public class AuditAppendException extends RuntimeException {

	public AuditAppendException(String message, Throwable cause) {
		super(message, cause);
	}
}
