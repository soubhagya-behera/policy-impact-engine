import { Link } from 'react-router-dom'
import { listImpactAssessments } from '../../api/impact'
import type { ImpactAssessmentSummary } from '../../api/types'
import { policyDetailPath } from '../../app/routes'
import { PageContainer } from '../../components/layout/PageContainer'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Button } from '../../components/ui/Button'
import { PageHeader, Section } from '../../components/ui/Layout'
import { StatusChip } from '../../components/ui/StatusChip'
import { useAsyncData } from '../../hooks/useAsyncData'
import { bandColor, bandLabel, formatRelativeTime } from '../../lib/format'

/**
 * Personal impact assessments, newest first, from
 * `GET /api/v1/me/impact-assessments`.
 *
 * Summaries only: each row carries the persisted score and band, never
 * a recomputation, and per-assessment breakdowns stay behind the
 * detail endpoint, which this UI does not surface yet.
 */
export function AssessmentsPage() {
  const assessments = useAsyncData<ImpactAssessmentSummary[]>(
    (signal) => listImpactAssessments({ page: 0, size: 20 }, signal),
    (data) => data.length === 0,
  )

  return (
    <PageContainer>
      <PageHeader
        label="Assessments"
        title="Privacy impact assessments"
        description="What each observed policy change means for you, newest first."
        actions={
          <Button
            variant="secondary"
            onClick={assessments.reload}
            isLoading={assessments.status === 'loading'}
          >
            Refresh
          </Button>
        }
      />

      <Section
        title="Your assessments"
        description="Scores follow your privacy profile; updating preferences only affects future assessments."
      >
        {assessments.isInitialLoading ? <SkeletonRows rows={5} /> : null}

        {assessments.status === 'error' && assessments.errorMessage ? (
          <ErrorState
            message={assessments.errorMessage}
            onRetry={assessments.reload}
          />
        ) : null}

        {assessments.isEmpty ? (
          <EmptyState
            title="No assessments yet"
            description="Once a tracked policy is checked and changes, its privacy impact is assessed and listed here."
          />
        ) : null}

        {assessments.data && assessments.data.length > 0 ? (
          <ul className="divide-y divide-line border-y border-line">
            {assessments.data.map((assessment) => (
              <li
                key={assessment.id}
                className="flex flex-col gap-2 py-5 sm:flex-row sm:items-center sm:justify-between"
              >
                <div className="min-w-0">
                  <p className="font-body text-base text-ink">
                    {bandLabel(assessment.aggregateBand)} ·{' '}
                    {assessment.aggregateScore}
                  </p>
                  <p className="mt-1 font-body text-sm text-ink-ghost">
                    v{assessment.previousVersionNumber} → v
                    {assessment.versionNumber} ·{' '}
                    {formatRelativeTime(assessment.createdAt)} ·{' '}
                    <Link
                      to={policyDetailPath(assessment.policyId)}
                      className="underline underline-offset-4 transition-colors duration-150 ease-standard hover:text-ink"
                    >
                      View policy
                    </Link>
                  </p>
                </div>
                <StatusChip
                  label={bandLabel(assessment.aggregateBand)}
                  tone={
                    assessment.aggregateBand === 'NONE' ? 'neutral' : 'accent'
                  }
                  className={bandColor(assessment.aggregateBand)}
                />
              </li>
            ))}
          </ul>
        ) : null}
      </Section>
    </PageContainer>
  )
}
