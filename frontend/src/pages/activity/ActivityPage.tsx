import { listAuditEvents } from '../../api/audit'
import { PageContainer } from '../../components/layout/PageContainer'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Button } from '../../components/ui/Button'
import { PageHeader, Section } from '../../components/ui/Layout'
import { useAsyncData } from '../../hooks/useAsyncData'
import { formatDateTime } from '../../lib/format'
import type { AuditEvent } from '../../api/types'

/**
 * Activity foundation, backed by the real audit feed from
 * `GET /api/v1/me/audit-events` (newest first, hash-chained).
 *
 * Event metadata is deliberately not parsed or rendered: the backend returns
 * it as an opaque JSON string, and decoding it here would invent a schema the
 * contract does not define.
 */
export function ActivityPage() {
  const events = useAsyncData<AuditEvent[]>(
    (signal) => listAuditEvents({ page: 0, size: 25 }, signal),
    (data) => data.length === 0,
  )

  return (
    <PageContainer>
      <PageHeader
        label="Activity"
        title="Everything the engine has recorded"
        description="A tamper-evident record of the actions taken on your account, newest first."
      />

      <Section
        title="Audit feed"
        description="Read-only. Listing this feed never records a new event."
        actions={
          <Button
            variant="secondary"
            onClick={events.reload}
            isLoading={events.status === 'loading'}
          >
            Refresh
          </Button>
        }
      >
        {events.isInitialLoading ? <SkeletonRows rows={5} /> : null}

        {events.status === 'error' && events.errorMessage ? (
          <ErrorState message={events.errorMessage} onRetry={events.reload} />
        ) : null}

        {events.isEmpty ? (
          <EmptyState
            title="No activity recorded yet"
            description="Actions such as registering a policy, signing in, or changing your privacy profile will be listed here."
          />
        ) : null}

        {events.data && events.data.length > 0 ? (
          <ol className="divide-y divide-line border-y border-line">
            {events.data.map((event) => (
              <li
                key={event.id}
                className="flex flex-col gap-2 py-5 sm:flex-row sm:items-center sm:justify-between"
              >
                <div className="min-w-0">
                  <p className="font-body text-base text-ink">
                    {event.eventType}
                  </p>
                  <p className="mt-1 truncate font-body text-sm text-ink-ghost">
                    {event.resourceType}
                    {event.resourceId ? ` · ${event.resourceId}` : ''}
                  </p>
                </div>
                <time
                  dateTime={event.occurredAt}
                  className="shrink-0 font-body text-sm tabular-nums text-ink-ghost"
                >
                  {formatDateTime(event.occurredAt)}
                </time>
              </li>
            ))}
          </ol>
        ) : null}
      </Section>
    </PageContainer>
  )
}
