import { Link, useParams } from 'react-router-dom'
import { getPolicyOverview } from '../../api/policies'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Button } from '../../components/ui/Button'
import { PageContainer } from '../../components/layout/PageContainer'
import { PageHeader, Section } from '../../components/ui/Layout'
import { StatusChip } from '../../components/ui/StatusChip'
import { useAsyncData } from '../../hooks/useAsyncData'
import {
  bandColor,
  bandLabel,
  formatDateTime,
  statusLabel,
} from '../../lib/format'
import { ROUTES } from '../../app/routes'
import type { PolicyOverview } from '../../api/types'

/**
 * Policy detail foundation.
 *
 * Phase 18-A establishes the shell and surfaces the real overview from
 * `GET /api/v1/policies/{policyId}/overview` (latest version, latest check,
 * latest impact). Version history, diffs, and change records are deliberately
 * left for a later phase rather than faked here.
 */
export function PolicyDetailPage() {
  const { policyId } = useParams<{ policyId: string }>()

  const overview = useAsyncData<PolicyOverview>(
    (signal) => getPolicyOverview(policyId ?? '', signal),
    () => false,
    { enabled: Boolean(policyId) },
  )

  if (!policyId) {
    return (
      <PageContainer>
        <EmptyState
          title="Policy not found"
          description="That policy reference is missing from the URL."
          action={
            <Link to={ROUTES.policies} className="inline-flex">
              <Button variant="secondary">Back to policies</Button>
            </Link>
          }
        />
      </PageContainer>
    )
  }

  const data = overview.data

  return (
    <PageContainer>
      <PageHeader
        label="Policy"
        title={data?.name ?? 'Policy detail'}
        {...(data?.url ? { description: data.url } : {})}
        actions={
          <Link to={ROUTES.policies}>
            <Button variant="secondary">Back to policies</Button>
          </Link>
        }
      />

      {overview.isInitialLoading ? <SkeletonRows rows={3} /> : null}

      {overview.status === 'error' && overview.errorMessage ? (
        <ErrorState message={overview.errorMessage} onRetry={overview.reload} />
      ) : null}

      {data ? (
        <>
          <Section title="Current state">
            <dl className="grid grid-cols-1 gap-x-8 gap-y-8 sm:grid-cols-3">
              <div>
                <dt className="type-label text-ink-faint">Status</dt>
                <dd className="mt-3">
                  <StatusChip
                    label={statusLabel(data.status)}
                    tone={data.status === 'ACTIVE' ? 'positive' : 'neutral'}
                  />
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Latest version</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  {data.latestVersion
                    ? `v${data.latestVersion.versionNumber}`
                    : 'Not observed yet'}
                </dd>
                {data.latestVersion ? (
                  <dd className="mt-1 font-body text-sm text-ink-ghost">
                    <time dateTime={data.latestVersion.observedAt}>
                      {formatDateTime(data.latestVersion.observedAt)}
                    </time>
                  </dd>
                ) : null}
              </div>
              <div>
                <dt className="type-label text-ink-faint">Privacy impact</dt>
                <dd
                  className={`mt-3 font-body text-base ${
                    data.latestImpact ? bandColor(data.latestImpact.band) : 'text-ink'
                  }`}
                >
                  {data.latestImpact
                    ? `${bandLabel(data.latestImpact.band)} (${data.latestImpact.aggregateScore})`
                    : 'Not assessed yet'}
                </dd>
              </div>
            </dl>
          </Section>

          <Section
            title="Latest check"
            description="The most recent fetch attempt for this document."
          >
            {data.latestCheck ? (
              <div className="border-y border-line py-6">
                <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
                  <span className="font-body text-base text-ink">
                    {data.latestCheck.status}
                  </span>
                  <span className="font-body text-sm text-ink-ghost">
                    Attempt {data.latestCheck.attemptNumber} ·{' '}
                    {data.latestCheck.trigger}
                  </span>
                  {data.latestCheck.httpStatus ? (
                    <span className="font-body text-sm text-ink-ghost">
                      HTTP {data.latestCheck.httpStatus}
                    </span>
                  ) : null}
                </div>
                <p className="mt-3 font-body text-sm text-ink-ghost">
                  <time dateTime={data.latestCheck.startedAt}>
                    {formatDateTime(data.latestCheck.startedAt)}
                  </time>
                </p>
              </div>
            ) : (
              <EmptyState
                title="No checks recorded"
                description="This policy has not been checked yet. The next check is scheduled automatically."
              />
            )}
          </Section>
        </>
      ) : null}
    </PageContainer>
  )
}
