package com.soubhagya.policyimpactengine.recommendation;

import java.util.NoSuchElementException;

/**
 * Not-found signal for user-scoped recommendation reads.
 *
 * <p>Extends {@link NoSuchElementException} (not
 * {@link IllegalArgumentException}) so missing or foreign
 * recommendations map to HTTP 404 rather than 400, following the
 * existing notification/policy isolation convention: cross-user access
 * behaves as not-found and never reveals whether the row exists.
 */
public class RecommendationNotFoundException extends NoSuchElementException {

	public RecommendationNotFoundException(String message) {
		super(message);
	}
}
