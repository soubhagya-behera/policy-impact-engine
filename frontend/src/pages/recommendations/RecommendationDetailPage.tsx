import { Link, useParams } from 'react-router-dom'
import { isApiError, toErrorMessage } from '../../api/errors'
import { getRecommendationDetail } from '../../api/recommendations'
import type {
  ImpactBand,
  RecommendationDetail,
} from '../../api/types'
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
import {
  actionKindLabel,
  bandColor,
  bandLabel,
  formatDateTime,
  formatRelativeTime,
} from '../../lib/format'

/**
 * User-facing message for a failed recommendation load. Unknown or
 * foreign ids answer `404` without revealing whether the row exists;
 * every other failure surfaces the backend's own message verbatim.
 */
export function toRecommendationError(error: unknown): string {
  if (isApiError(error) && error.status === 404) {
    return 'This recommendation no longer exists.'
  }
  return toErrorMessage(error)
}

/**
 * Display facts for one persisted recommendation, derived from
 * `GET /api/v1/me/recommendations/{recommendationId}`.
 *
 * Every value below is a persisted fact rendered verbatim — nothing
 * is recomputed here. `conceptCode` stays `null` for the
 * assessment-level `NONE_REQUIRED` closure row, which belongs to no
 * single concept; the view omits the concept field in that case
 * instead of printing a literal "null".
 */
export interface RecommendationFacts {
  headline: string
  conceptCode: string | null
  score: number
  band: ImpactBand
  bandDisplay: string
  ruleId: string
  ruleOrder: number
  rulesVersion: number
  assessedDisplay: string
  assessedValue: string
  ageDisplay: string
  policyPath: string
  versionDisplay: string
  assessmentId: string
}

export function toRecommendationFacts(
  detail: RecommendationDetail,
): RecommendationFacts {
  return {
    headline: actionKindLabel(detail.actionKind),
    conceptCode: detail.conceptCode,
    score: detail.personalizedNormalized,
    band: detail.personalizedBand,
    bandDisplay: bandLabel(detail.personalizedBand),
    ruleId: detail.ruleId,
    ruleOrder: detail.ruleOrder,
    rulesVersion: detail.recommendationRulesVersion,
    assessedDisplay: formatDateTime(detail.createdAt),
    assessedValue: detail.createdAt,
    ageDisplay: formatRelativeTime(detail.createdAt),
    policyPath: policyDetailPath(detail.policyId),
    versionDisplay: `Version ${detail.versionNumber}`,
    assessmentId: detail.assessmentId,
  }
}

/**
 * One persisted recommendation with its assessment navigation, from
 * `GET /api/v1/me/recommendations/{recommendationId}`.
 */
export function RecommendationDetailPage() {
  const { recommendationId } = useParams<{ recommendationId: string }>()

  const detail = useAsyncData<RecommendationDetail>(
    (signal) =>
      getRecommendationDetail(recommendationId ?? '', signal).catch(
        (error: unknown) => {
          // Map here so the shared error state carries the user-facing
          // message; aborts still propagate untouched for cancellation.
          if (error instanceof DOMException && error.name === 'AbortError') {
            throw error
          }
          throw new Error(toRecommendationError(error))
        },
      ),
    () => false,
    { enabled: Boolean(recommendationId) },
  )

  if (!recommendationId) {
    return (
      <PageContainer>
        <EmptyState
          title="Recommendation not found"
          description="That recommendation reference is missing from the URL."
          action={
            <Link to={ROUTES.app} className="inline-flex">
              <Button variant="secondary">Back to overview</Button>
            </Link>
          }
        />
      </PageContainer>
    )
  }

  const data = detail.data
  const facts = data ? toRecommendationFacts(data) : null

  return (
    <PageContainer>
      <PageHeader
        label="Recommendation"
        title={facts ? facts.headline : 'Recommendation'}
        {...(facts ? { description: facts.versionDisplay } : {})}
        actions={
          <>
            {facts ? (
              <Link to={facts.policyPath}>
                <Button variant="secondary">View policy</Button>
              </Link>
            ) : null}
            <Link to={ROUTES.app}>
              <Button variant="secondary">Back to overview</Button>
            </Link>
          </>
        }
      />

      {detail.isInitialLoading ? <SkeletonRows rows={3} /> : null}

      {detail.status === 'error' && detail.errorMessage ? (
        <ErrorState message={detail.errorMessage} onRetry={detail.reload} />
      ) : null}

      {facts ? (
        <>
          <Section title="Recommended action">
            <dl className="grid grid-cols-1 gap-x-8 gap-y-8 sm:grid-cols-3">
              <div>
                <dt className="type-label text-ink-faint">Action</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  {facts.headline}
                </dd>
              </div>
              {facts.conceptCode ? (
                <div>
                  <dt className="type-label text-ink-faint">Concept</dt>
                  <dd className="mt-3 font-body text-base text-ink">
                    {facts.conceptCode}
                  </dd>
                </div>
              ) : null}
              <div>
                <dt className="type-label text-ink-faint">Band</dt>
                <dd className="mt-3">
                  <StatusChip
                    label={facts.bandDisplay}
                    tone={facts.band === 'NONE' ? 'neutral' : 'accent'}
                    className={bandColor(facts.band)}
                  />
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Score</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  {facts.score}
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Recommended</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  <time dateTime={facts.assessedValue}>
                    {facts.assessedDisplay}
                  </time>
                </dd>
              </div>
            </dl>
          </Section>

          <Section title="Rule">
            <dl className="grid grid-cols-1 gap-x-8 gap-y-8 sm:grid-cols-3">
              <div>
                <dt className="type-label text-ink-faint">Rule</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  {facts.ruleId}
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Order</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  {facts.ruleOrder}
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Rules version</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  v{facts.rulesVersion}
                </dd>
              </div>
            </dl>
          </Section>

          <Section title="Source">
            <dl className="grid grid-cols-1 gap-x-8 gap-y-8 sm:grid-cols-3">
              <div>
                <dt className="type-label text-ink-faint">Policy version</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  <Link
                    to={facts.policyPath}
                    className="underline underline-offset-4"
                  >
                    {facts.versionDisplay}
                  </Link>{' '}
                  <span className="text-sm text-ink-ghost">
                    ({facts.ageDisplay})
                  </span>
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Assessment</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  {facts.assessmentId}
                </dd>
              </div>
            </dl>
          </Section>
        </>
      ) : null}
    </PageContainer>
  )
}
