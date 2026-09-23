package com.soubhagya.policyimpactengine.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/**
 * Phase 13-B — unit tests for the shared feed-pagination contract
 * (see DECISIONS.md ADR-026). Pure unit tests: no Spring context, no
 * database. HTTP-level behavior (defaults, binding, 400/401 mapping)
 * is covered by the feed controller slice tests.
 */
class FeedPaginationTest {

	@Test
	void contractConstantsMatchAdr026() {
		assertThat(FeedPagination.DEFAULT_PAGE).isZero();
		assertThat(FeedPagination.DEFAULT_SIZE).isEqualTo(20);
		assertThat(FeedPagination.MAX_SIZE).isEqualTo(100);
	}

	@Test
	void acceptsDefaultsAndMaximum() {
		assertThat(FeedPagination.of(0, 20)).isEqualTo(new FeedPagination(0, 20));
		assertThat(FeedPagination.of(3, 100)).isEqualTo(new FeedPagination(3, 100));
		assertThat(FeedPagination.of(0, 1)).isEqualTo(new FeedPagination(0, 1));
	}

	@Test
	void rejectsNegativePage() {
		assertThatThrownBy(() -> FeedPagination.of(-1, 20))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("page");
	}

	@Test
	void rejectsZeroAndNegativeSize() {
		assertThatThrownBy(() -> FeedPagination.of(0, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("size");
		assertThatThrownBy(() -> FeedPagination.of(0, -5))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("size");
	}

	@Test
	void rejectsSizeAboveMaximumWithoutClamping() {
		assertThatThrownBy(() -> FeedPagination.of(0, 101))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("100");
	}

	@Test
	void pageRequestCarriesPageSizeAndCallerSort() {
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

		assertThat(FeedPagination.of(2, 5).pageRequest(sort).getPageNumber()).isEqualTo(2);
		assertThat(FeedPagination.of(2, 5).pageRequest(sort).getPageSize()).isEqualTo(5);
		assertThat(FeedPagination.of(2, 5).pageRequest(sort).getSort()).isEqualTo(sort);
		assertThat(FeedPagination.of(0, 20).pageRequest(sort).isPaged()).isTrue();
	}

	@Test
	void pageRequestRejectsNullSort() {
		assertThatThrownBy(() -> FeedPagination.of(0, 20).pageRequest(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
