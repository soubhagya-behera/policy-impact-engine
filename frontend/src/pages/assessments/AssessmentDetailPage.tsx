import { Link, useParams } from 'react-router-dom'
import { getImpactAssessment } from '../../api/impact'
import type { ImpactAssessmentDetail } from '../../api/types'
import { policyDetailPath, ROUTES } from '../../app/routes'
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
import { isApiError, toErrorMessage } from '../../api/errors'
import {
  bandColor,
  bandLabel,
  changeTypeLabel,
  formatDateTime,
} from '../../lib/format'

/**
 * User-facing message for a failed assessment load. Unknown or
 * foreign ids answer `404` without revealing whether the row exists;
 * every other failure surfaces the backend's own message verbatim.
 */
export function toAssessmentError(error: unknown): string {
  if (isApiError(error) && error.status === 404) {
    return 'This assessment no longer exists.'
  }
  return toErrorMessage(error)
}

/**
 * One persisted impact assessment with its ordered score breakdown,
 * from `GET /api/v1/me/impact-assessments/{assessmentId}`.
 *
 * Every value below is a persisted fact rendered verbatim — nothing
 * is recomputed here. Breakdown rows keep the backend's order.
 */
export function AssessmentDetailPage() {
  const { assessmentId } = useParams<{ assessmentId: string }>()

  const detail = useAsyncData<ImpactAssessmentDetail>(
    (signal) =>
      getImpactAssessment(assessmentId ?? '', signal).catch((error: unknown) => {
        // Map here so the shared error state carries the user-facing
        // message; aborts still propagate untouched for cancellation.
        if (error instanceof DOMException && error.name === 'AbortError') {
          throw error
        }
        throw new Error(toAssessmentError(error))
      }),
    () => false,
    { enabled: Boolean(assessmentId) },
  )

  if (!assessmentId) {
    return (
      <PageContainer>
        <EmptyState
          title="Assessment not found"
          description="That assessment reference is missing from the URL."
          action={
            <Link to={ROUTES.assessments} className="inline-flex">
              <Button variant="secondary">Back to assessments</Button>
            </Link>
          }
        />
      </PageContainer>
    )
  }

  const data = detail.data

  return (
    <PageContainer>
      <PageHeader
        label="Assessment"
        title={
          data
            ? `${bandLabel(data.aggregateBand)} · ${data.aggregateScore}`
            : 'Impact assessment'
        }
        {...(data
          ? {
              description: `v${data.previousVersionNumber} → v${data.versionNumber}`,
            }
          : {})}
        actions={
          <>
            {data ? (
              <Link to={policyDetailPath(data.policyId)}>
                <Button variant="secondary">View policy</Button>
              </Link>
            ) : null}
            <Link to={ROUTES.assessments}>
              <Button variant="secondary">Back to assessments</Button>
            </Link>
          </>
        }
      />

      {detail.isInitialLoading ? <SkeletonRows rows={3} /> : null}

      {detail.status === 'error' && detail.errorMessage ? (
        <ErrorState message={detail.errorMessage} onRetry={detail.reload} />
      ) : null}

      {data ? (
        <>
          <Section title="Assessment">
            <dl className="grid grid-cols-1 gap-x-8 gap-y-8 sm:grid-cols-3">
              <div>
                <dt className="type-label text-ink-faint">Band</dt>
                <dd className="mt-3">
                  <StatusChip
                    label={bandLabel(data.aggregateBand)}
                    tone={data.aggregateBand === 'NONE' ? 'neutral' : 'accent'}
                    className={bandColor(data.aggregateBand)}
                  />
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Assessed</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  <time dateTime={data.createdAt}>
                    {formatDateTime(data.createdAt)}
                  </time>
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Rules</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  v{data.personalizationRulesVersion}
                </dd>
              </div>
            </dl>
          </Section>

          <Section
            title="Score breakdown"
            description="Why this score, line by line, in backend order."
          >
            {data.breakdowns.length > 0 ? (
              <ul className="divide-y divide-line border-y border-line">
                {data.breakdowns.map((row, index) => (
                  <li key={row.changeImpactId} className="py-5">
                    <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
                      <span className="font-body text-base text-ink">
                        {row.conceptCode} · {changeTypeLabel(row.changeType)}
                      </span>
                      <span className="font-body text-sm text-ink-ghost">
                        Personalized {row.personalizedNormalized} (
                        {bandLabel(row.personalizedBand)})
                      </span>
                      <StatusChip
                        label={bandLabel(row.personalizedBand)}
                        tone={
                          row.personalizedBand === 'NONE' ? 'neutral' : 'accent'
                        }
                        className={bandColor(row.personalizedBand)}
                      />
                    </div>
                    <p className="mt-3 font-body text-sm text-ink-ghost">
                      System {row.systemNormalized} (
                      {bandLabel(row.systemBand)}) · Sensitivity{' '}
                      {row.effectiveSensitivity}/5
                    </p>
                    <p className="mt-1 font-body text-xs text-ink-faint">
                      Line {index + 1} · rules v
                      {row.personalizationRulesVersion}
                    </p>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState
                title="No breakdown rows recorded"
                description="This assessment carries no per-concept score lines."
              />
            )}
          </Section>
        </>
      ) : null}
    </PageContainer>
  )
}
