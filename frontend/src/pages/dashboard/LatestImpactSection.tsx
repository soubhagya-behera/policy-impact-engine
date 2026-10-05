import { EmptyState, ErrorState, SkeletonRows } from '../../components/ui/AsyncState'
import { Section } from '../../components/ui/Layout'
import {
  bandColor,
  bandDescription,
  bandLabel,
  formatRelativeTime,
} from '../../lib/format'
import type { AsyncState } from '../../hooks/useAsyncData'
import type { ImpactSummary } from '../../api/types'

/**
 * Most recent impact assessment, rendered from
 * `GET /api/v1/me/impact-summary`. A summary with no assessments yet is a
 * genuine empty state, not a failure.
 */
export function LatestImpactSection({
  state,
}: {
  state: AsyncState<ImpactSummary>
}) {
  const latest = state.data?.latest ?? null

  return (
    <Section
      title="Latest impact"
      description="Your most recent privacy impact assessment."
    >
      {state.isInitialLoading ? <SkeletonRows rows={1} /> : null}

      {state.status === 'error' && state.errorMessage ? (
        <ErrorState message={state.errorMessage} onRetry={state.reload} />
      ) : null}

      {latest ? (
        <div className="border-y border-line py-8">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <p
                className={`type-section ${bandColor(latest.aggregateBand)}`}
              >
                {bandLabel(latest.aggregateBand)}
              </p>
              <p className="mt-3 font-body text-base text-ink-muted">
                {bandDescription(latest.aggregateBand)}
              </p>
            </div>
            <div className="sm:text-right">
              <p className="type-section tabular-nums text-ink">
                {latest.aggregateScore}
              </p>
              <p className="type-label mt-3 text-ink-faint">
                Aggregate score
              </p>
            </div>
          </div>
          <p className="mt-6 font-body text-sm text-ink-ghost">
            Version {latest.previousVersionNumber} → {latest.versionNumber} ·{' '}
            {formatRelativeTime(latest.createdAt)}
          </p>
        </div>
      ) : null}

      {state.status === 'success' && !latest ? (
        <EmptyState
          title="No assessments yet"
          description="Once a tracked policy is checked and changes, its privacy impact is assessed and summarised here."
        />
      ) : null}
    </Section>
  )
}
