package com.soubhagya.policyimpactengine.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditMetadata;
import com.soubhagya.policyimpactengine.diff.PolicyChangeType;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;
import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecordRepository;
import com.soubhagya.policyimpactengine.impact.ImpactAssessmentService;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpact;
import com.soubhagya.policyimpactengine.impact.domain.ChangeImpactRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessment;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentBreakdownRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactAssessmentRepository;
import com.soubhagya.policyimpactengine.impact.domain.ImpactBand;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatch;
import com.soubhagya.policyimpactengine.intelligence.domain.ChangeConceptMatchRepository;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConcept;
import com.soubhagya.policyimpactengine.intelligence.domain.PrivacyConceptRepository;
import com.soubhagya.policyimpactengine.notification.application.NotificationService;
import com.soubhagya.policyimpactengine.notification.domain.Notification;
import com.soubhagya.policyimpactengine.notification.domain.NotificationRepository;
import com.soubhagya.policyimpactengine.policy.application.PolicyService;
import com.soubhagya.policyimpactengine.policy.domain.Policy;
import com.soubhagya.policyimpactengine.policy.domain.PolicyRepository;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersion;
import com.soubhagya.policyimpactengine.policy.domain.PolicyVersionRepository;
import com.soubhagya.policyimpactengine.policy.fetch.Sha256PolicyContentHasher;
import com.soubhagya.policyimpactengine.recommendation.RecommendationService;
import com.soubhagya.policyimpactengine.recommendation.domain.Recommendation;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationActionKind;
import com.soubhagya.policyimpactengine.recommendation.domain.RecommendationRepository;
import com.soubhagya.policyimpactengine.user.domain.User;
import com.soubhagya.policyimpactengine.user.domain.UserPrivacyPreferenceRepository;
import com.soubhagya.policyimpactengine.user.domain.UserRepository;

/**
 * Phase 13-F measurement-only baseline (no production change).
 *
 * <p>Seeds 45 owned policies with one v1-&gt;v2 transition each (assessment,
 * recommendation, notification, audit row per transition) against real
 * Testcontainers PostgreSQL, then counts SELECT statements per paged feed
 * call via Hibernate {@link Statistics} at sizes 1/5/20/100 on pages 0/1.
 *
 * <p>Not {@code @Transactional}: each paged service call runs in its own
 * short read transaction, so counts reflect production page cost. No fetch
 * joins, EntityGraphs, batch hints, projections, or migrations here.
 */
@SpringBootTest
@Testcontainers
class FeedQueryCountBaselineTest {

	private static final int ROWS = 45;

	private static final int[] SIZES = {1, 5, 20, 100};

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserPrivacyPreferenceRepository preferenceRepository;

	@Autowired
	private PolicyRepository policyRepository;

	@Autowired
	private PolicyVersionRepository versionRepository;

	@Autowired
	private PolicyChangeRecordRepository changeRepository;

	@Autowired
	private PrivacyConceptRepository conceptRepository;

	@Autowired
	private ChangeConceptMatchRepository matchRepository;

	@Autowired
	private ChangeImpactRepository impactRepository;

	@Autowired
	private ImpactAssessmentRepository assessmentRepository;

	@Autowired
	private ImpactAssessmentBreakdownRepository breakdownRepository;

	@Autowired
	private RecommendationRepository recommendationRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private AuditEventRepository auditEventRepository;

	@Autowired
	private PolicyService policyService;

	@Autowired
	private ImpactAssessmentService assessmentService;

	@Autowired
	private RecommendationService recommendationService;

	@Autowired
	private NotificationService notificationService;

	@Autowired
	private AuditService auditService;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private final Sha256PolicyContentHasher hasher = new Sha256PolicyContentHasher();

	@BeforeEach
	void clean() {
		auditEventRepository.deleteAll();
		notificationRepository.deleteAll();
		recommendationRepository.deleteAll();
		breakdownRepository.deleteAll();
		assessmentRepository.deleteAll();
		impactRepository.deleteAll();
		matchRepository.deleteAll();
		changeRepository.deleteAll();
		versionRepository.deleteAll();
		policyRepository.deleteAll();
		preferenceRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void baselineSelectCountsPerFeed() {
		UUID userId = seed();
		StringBuilder table = new StringBuilder("\nFEED BASELINE (SELECTs per paged call, rows=").append(ROWS).append(")\n");
		for (int size : SIZES) {
			int rowsOnPage0 = Math.min(size, ROWS);
			int rowsOnPage1 = Math.max(0, Math.min(size, ROWS - size));
			long policies0 = selects(() -> policyService.listPaged(userId, 0, size));
			long policies1 = selects(() -> policyService.listPaged(userId, 1, size));
			long assessments0 = selects(() -> assessmentService.listAssessmentSummariesPaged(userId, 0, size));
			long assessments1 = selects(() -> assessmentService.listAssessmentSummariesPaged(userId, 1, size));
			long recs0 = selects(() -> recommendationService.listRecommendationResponsesPaged(userId, 0, size));
			long recs1 = selects(() -> recommendationService.listRecommendationResponsesPaged(userId, 1, size));
			long notifs0 = selects(() -> notificationService.listNotificationResponsesPaged(userId, 0, size));
			long notifs1 = selects(() -> notificationService.listNotificationResponsesPaged(userId, 1, size));
			long unread0 = selects(() -> notificationService.listUnreadNotificationResponsesPaged(userId, 0, size));
			long unread1 = selects(() -> notificationService.listUnreadNotificationResponsesPaged(userId, 1, size));
			long audit0 = selects(() -> auditService.listAuditEventResponsesPaged(userId, 0, size));
			long audit1 = selects(() -> auditService.listAuditEventResponsesPaged(userId, 1, size));
			table.append("size=").append(size)
					.append(" page0(rows=").append(rowsOnPage0).append(")")
					.append(" policies=").append(policies0)
					.append(" assessments=").append(assessments0)
					.append(" recs=").append(recs0)
					.append(" notifs=").append(notifs0)
					.append(" unread=").append(unread0)
					.append(" audit=").append(audit0)
					.append(" | page1(rows=").append(rowsOnPage1).append(")")
					.append(" policies=").append(policies1)
					.append(" assessments=").append(assessments1)
					.append(" recs=").append(recs1)
					.append(" notifs=").append(notifs1)
					.append(" unread=").append(unread1)
					.append(" audit=").append(audit1)
					.append("\n");

			// RECORD-ONLY pass: print raw counts for the full matrix; pattern
			// assertions are applied after the baseline is established.
			System.out.println("SIZE " + size + " policies0=" + policies0 + " policies1=" + policies1
					+ " assessments0=" + assessments0 + " assessments1=" + assessments1
					+ " recs0=" + recs0 + " recs1=" + recs1
					+ " notifs0=" + notifs0 + " notifs1=" + notifs1
					+ " unread0=" + unread0 + " unread1=" + unread1
					+ " audit0=" + audit0 + " audit1=" + audit1);
			int rowsOnPage1Final = rowsOnPage1;
			// Measured baseline (Hibernate 7, Testcontainers PostgreSQL 16):
			// - policies: page query + 1 shared-owner fetch = 2 constant (empty page = 1).
			// - assessments (Phase 13-F fix): fetch-join page query = constant.
			//   Ideally 1; at most 2 is tolerated for provider-specific join
			//   rendering, but it must NOT grow with N.
			// - recommendations / audit: 1 constant (proxy-ID / scalar-only DTOs).
			// - notifications / unread (NOT yet optimized): 1 + 2 per row
			//   (EAGER assessment select-fetch + lazy newVersion load).
			assertThat(policies0).as("policies size=%d page=0", size)
					.isEqualTo(rowsOnPage0 == 0 ? 1 : 2);
			assertThat(policies1).as("policies size=%d page=1", size)
					.isEqualTo(rowsOnPage1Final == 0 ? 1 : 2);
			assertThat(assessments0).as("assessments size=%d page=0", size)
					.isLessThanOrEqualTo(rowsOnPage0 == 0 ? 1 : 2);
			assertThat(assessments1).as("assessments size=%d page=1", size)
					.isLessThanOrEqualTo(rowsOnPage1Final == 0 ? 1 : 2);
			assertThat(recs0).as("recommendations size=%d page=0", size).isEqualTo(1);
			assertThat(recs1).as("recommendations size=%d page=1", size).isEqualTo(1);
			assertThat(notifs0).as("notifications size=%d page=0", size)
					.isEqualTo(1 + 2L * rowsOnPage0);
			assertThat(notifs1).as("notifications size=%d page=1", size)
					.isEqualTo(1 + 2L * rowsOnPage1Final);
			assertThat(unread0).as("unread size=%d page=0", size)
					.isEqualTo(1 + 2L * rowsOnPage0);
			assertThat(unread1).as("unread size=%d page=1", size)
					.isEqualTo(1 + 2L * rowsOnPage1Final);
			assertThat(audit0).as("audit size=%d page=0", size).isEqualTo(1);
			assertThat(audit1).as("audit size=%d page=1", size).isEqualTo(1);
		}
		System.out.println(table);

		// Sanity: feeds actually returned the seeded rows.
		assertThat(policyService.listPaged(userId, 0, 100)).hasSize(ROWS);
		assertThat(assessmentService.listAssessmentSummariesPaged(userId, 0, 100)).hasSize(ROWS);
		assertThat(recommendationService.listRecommendationResponsesPaged(userId, 0, 100)).hasSize(ROWS);
		assertThat(notificationService.listNotificationResponsesPaged(userId, 0, 100)).hasSize(ROWS);
		assertThat(notificationService.listUnreadNotificationResponsesPaged(userId, 0, 100)).hasSize(ROWS);
		assertThat(auditService.listAuditEventResponsesPaged(userId, 0, 100)).hasSize(ROWS);
	}

	@Test
	void diagnosePerRowFetches() {
		UUID userId = seed();
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);

		stats.clear();
		policyService.listPaged(userId, 0, 5);
		long userFetches = stats.getEntityStatistics(User.class.getName()).getFetchCount();
		long policyStmts = stats.getPrepareStatementCount();
		System.out.println("DIAGNOSE policies size=5 statements=" + policyStmts + " userFetches=" + userFetches);

		stats.clear();
		assessmentService.listAssessmentSummariesPaged(userId, 0, 2);
		long versionFetches = stats.getEntityStatistics(PolicyVersion.class.getName()).getFetchCount();
		long assessmentFetches = stats.getEntityStatistics(ImpactAssessment.class.getName()).getFetchCount();
		long assessmentStmts = stats.getPrepareStatementCount();
		System.out.println("DIAGNOSE assessments size=2 statements=" + assessmentStmts
				+ " policyVersionFetches=" + versionFetches + " assessmentFetches=" + assessmentFetches);
		// Phase 13-F fix: both versions resolve in the page query — no per-row fetches.
		assertThat(assessmentStmts).as("assessment page statements").isLessThanOrEqualTo(2);
		assertThat(versionFetches).as("assessment page version fetches").isEqualTo(0);
		assertThat(assessmentFetches).as("assessment page assessment fetches").isEqualTo(0);

		stats.clear();
		notificationService.listNotificationResponsesPaged(userId, 0, 2);
		long notifVersionFetches = stats.getEntityStatistics(PolicyVersion.class.getName()).getFetchCount();
		long notifAssessmentFetches = stats.getEntityStatistics(
				ImpactAssessment.class.getName()).getFetchCount();
		long notifStmts = stats.getPrepareStatementCount();
		System.out.println("DIAGNOSE notifications size=2 statements=" + notifStmts
				+ " policyVersionFetches=" + notifVersionFetches
				+ " assessmentFetches=" + notifAssessmentFetches);
		// Notifications NOT yet optimized: per-row assessment + version fetches remain.
		assertThat(notifStmts).as("notification page statements").isEqualTo(5);
		assertThat(notifVersionFetches).as("notification page version fetches").isEqualTo(2);
		assertThat(notifAssessmentFetches).as("notification page assessment fetches").isEqualTo(2);
	}

	private long selects(Runnable read) {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		stats.clear();
		read.run();
		return stats.getPrepareStatementCount();
	}

	private UUID seed() {
		User user = userRepository.saveAndFlush(new User());
		PrivacyConcept concept = conceptRepository.findByCode("LOCATION").orElseThrow();
		for (int i = 0; i < ROWS; i++) {
			Policy policy = new Policy("P " + i + " " + UUID.randomUUID(),
					"https://example.com/" + UUID.randomUUID());
			policy.setOwner(user);
			policyRepository.saveAndFlush(policy);
			String oldText = "old text " + i + " " + UUID.randomUUID();
			String newText = "new text " + i + " location clause " + UUID.randomUUID();
			PolicyVersion v1 = versionRepository.saveAndFlush(
					new PolicyVersion(policy, 1, oldText, hasher.hash(oldText)));
			PolicyVersion v2 = versionRepository.saveAndFlush(
					new PolicyVersion(policy, 2, newText, hasher.hash(newText)));
			PolicyChangeRecord change = changeRepository.saveAndFlush(new PolicyChangeRecord(
					v1, v2, PolicyChangeType.MODIFIED, oldText, newText, 0));
			ChangeConceptMatch match = matchRepository.saveAndFlush(new ChangeConceptMatch(
					change, concept, "location", "LOCATION:seed", "KEYWORD"));
			impactRepository.saveAndFlush(new ChangeImpact(match, "LOCATION", "MODIFIED", 8,
					new BigDecimal("1.00"), new BigDecimal("8.00"), 80, ImpactBand.CRITICAL, 1));
			ImpactAssessment assessment = assessmentRepository.saveAndFlush(
					new ImpactAssessment(user, v2, v1, 80, ImpactBand.CRITICAL, 1));
			recommendationRepository.saveAndFlush(new Recommendation(assessment, "REC-TEST", 1,
					RecommendationActionKind.REVIEW_SETTINGS, "LOCATION", 80, ImpactBand.HIGH, 1));
			notificationRepository.saveAndFlush(new Notification(assessment,
					Instant.parse("2026-09-18T10:00:00Z").plusSeconds(i * 60L)));
			auditService.append(user.getId(), AuditEventType.POLICY_REGISTERED, "POLICY",
					policy.getId(), AuditMetadata.empty(), null);
		}
		return user.getId();
	}
}
