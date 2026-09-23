package com.soubhagya.policyimpactengine.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Phase 13-B — shared feed-pagination contract (see DECISIONS.md
 * ADR-026).
 *
 * <p>All user-facing listing feeds accept {@code page} (default 0) and
 * {@code size} (default 20, maximum 100) and return a bare JSON array
 * with no envelope and no total count. Validation is explicit and
 * fail-fast: out-of-range values throw
 * {@code IllegalArgumentException}, which the global handler maps to a
 * 400 {@code application/problem+json} response. Sizes are rejected,
 * never silently clamped.
 *
 * <p>Malformed or overflowing numeric values never reach this type:
 * Spring rejects them at binding time through the existing
 * type-mismatch handler (also 400). Duplicate query parameters follow
 * Spring's existing first-value binding.
 */
public record FeedPagination(int page, int size) {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	public static FeedPagination of(int page, int size) {
		if (page < 0) {
			throw new IllegalArgumentException("page must be >= 0, got: " + page);
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new IllegalArgumentException(
					"size must be between 1 and " + MAX_SIZE + ", got: " + size);
		}
		return new FeedPagination(page, size);
	}

	/**
	 * Builds the repository query window for the given deterministic
	 * ordering. The {@code Sort} carries the feed's exact ordering
	 * (including every approved id tie-break); it must not be null.
	 */
	public Pageable pageRequest(Sort sort) {
		if (sort == null) {
			throw new IllegalArgumentException("Sort must not be null");
		}
		return PageRequest.of(page, size, sort);
	}
}
