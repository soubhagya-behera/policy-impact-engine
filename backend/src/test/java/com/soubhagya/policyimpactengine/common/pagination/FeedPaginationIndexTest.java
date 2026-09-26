package com.soubhagya.policyimpactengine.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 13-B — guards the V18 index baseline the paginated feeds rely
 * on (see DECISIONS.md ADR-026 §11). Asserts the two proven covering
 * indexes exist with their exact column orderings, and that no
 * migration beyond V19 is present. No query behavior is tested here;
 * feed ordering and pagination behavior live in the feed integration
 * tests. EXPLAIN evidence for these indexes was recorded during
 * Phase 13-A/13-B planning, not as brittle committed assertions.
 */
@SpringBootTest
@Testcontainers
class FeedPaginationIndexTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v18CoveringIndexesExistWithExactColumnOrdering() {
		assertThat(indexDefinition("idx_policy_owner_created"))
				.contains("(owner_id, created_at, id)");
		assertThat(indexDefinition("idx_assessment_user_created"))
				.contains("(user_id, created_at DESC, id DESC)");
	}

	@Test
	void schemaVersionIsExactlyV19() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
				Integer.class);
		assertThat(applied).isEqualTo(19);

		String latest = jdbcTemplate.queryForObject(
				"SELECT version FROM flyway_schema_history WHERE success = true"
						+ " ORDER BY installed_rank DESC LIMIT 1",
				String.class);
		assertThat(latest).isEqualTo("19");
	}

	private String indexDefinition(String indexName) {
		return jdbcTemplate.queryForObject(
				"SELECT indexdef FROM pg_indexes WHERE indexname = ?",
				String.class, indexName);
	}
}
