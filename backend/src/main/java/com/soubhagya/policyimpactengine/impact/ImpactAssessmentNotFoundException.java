package com.soubhagya.policyimpactengine.impact;

import java.util.NoSuchElementException;

/**
 * Not-found signal for user-scoped impact-assessment reads.
 *
 * <p>Extends {@link NoSuchElementException} (not
 * {@link IllegalArgumentException}) so missing or foreign assessments
 * map to HTTP 404 rather than 400, following the existing
 * notification/policy isolation convention: cross-user access behaves
 * as not-found and never reveals whether the row exists.
 */
public class ImpactAssessmentNotFoundException extends NoSuchElementException {

	public ImpactAssessmentNotFoundException(String message) {
		super(message);
	}
}
