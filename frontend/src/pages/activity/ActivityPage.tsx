import { useCallback, useRef, useState } from 'react'
import { AUDIT_FEED_PAGE_SIZE, listAuditEvents } from '../../api/audit'
import { toErrorMessage } from '../../api/errors'
import { PageContainer } from '../../components/layout/PageContainer'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Button } from '../../components/ui/Button'
import { PageHeader, Section } from '../../components/ui/Layout'
import { useAsyncData } from '../../hooks/useAsyncData'
import { auditEventLabel, formatDateTime } from '../../lib/format'
import type { AuditEvent } from '../../api/types'

/**
 * Activity foundation, backed by the real audit feed from
 * `GET /api/v1/me/audit-events` (newest first, hash-chained).
 *
 * Event metadata is deliberately not parsed or rendered: the backend returns
 * it as an opaque JSON string, and decoding it here would invent a schema the
 * contract does not define.
 *
 * Pagination: the backend returns a bare array with no total count, so a
 * short page (`length < AUDIT_FEED_PAGE_SIZE`) is the end-of-feed signal.
 * Page 0 loads with the initial request; "Load more" appends each following
 * page in backend order without touching already-loaded events.
 */
export function ActivityPage() {
  const events = useAsyncData<AuditEvent[]>(
    (signal) =>
      listAuditEvents({ page: 0, size: AUDIT_FEED_PAGE_SIZE }, signal),
    (data) => data.length === 0,
  )

  const [extraEvents, setExtraEvents] = useState<AuditEvent[]>([])
  const [nextPage, setNextPage] = useState(1)
  /** Length of the most recently appended page; null until one is loaded. */
  const [lastFetchedCount, setLastFetchedCount] = useState<number | null>(null)
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null)
  /** Guards against a stale page response landing after a refresh. */
  const sessionRef = useRef(0)

  const firstPage = events.data ?? []
  const allEvents = [...firstPage, ...extraEvents]
  const hasMore =
    events.status === 'success' &&
    (lastFetchedCount === null
      ? firstPage.length === AUDIT_FEED_PAGE_SIZE
      : lastFetchedCount === AUDIT_FEED_PAGE_SIZE)

  const handleRefresh = useCallback(() => {
    sessionRef.current += 1
    setExtraEvents([])
    setNextPage(1)
    setLastFetchedCount(null)
    setLoadMoreError(null)
    setIsLoadingMore(false)
    events.reload()
  }, [events.reload])

  const loadMore = useCallback(async () => {
    if (isLoadingMore) return
    const session = sessionRef.current
    const page = nextPage
    const seenIds = new Set(allEvents.map((event) => event.id))
    setIsLoadingMore(true)
    setLoadMoreError(null)
    try {
      const pageEvents = await listAuditEvents({
        page,
        size: AUDIT_FEED_PAGE_SIZE,
      })
      if (sessionRef.current !== session) return
      setExtraEvents((previous) => [
        ...previous,
        ...pageEvents.filter((event) => !seenIds.has(event.id)),
      ])
      setNextPage(page + 1)
      setLastFetchedCount(pageEvents.length)
    } catch (error) {
      if (sessionRef.current !== session) return
      setLoadMoreError(toErrorMessage(error))
    } finally {
      if (sessionRef.current === session) setIsLoadingMore(false)
    }
  }, [isLoadingMore, nextPage, events.data, extraEvents])

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
            onClick={handleRefresh}
            isLoading={events.status === 'loading'}
          >
            Refresh
          </Button>
        }
      >
        {events.isInitialLoading ? <SkeletonRows rows={5} /> : null}

        {events.status === 'error' && events.errorMessage ? (
          <ErrorState
            message={events.errorMessage}
            onRetry={handleRefresh}
          />
        ) : null}

        {events.isEmpty ? (
          <EmptyState
            title="No activity recorded yet"
            description="Actions such as registering a policy, signing in, or changing your privacy profile will be listed here."
          />
        ) : null}

        {allEvents.length > 0 ? (
          <>
            <ol className="divide-y divide-line border-y border-line">
              {allEvents.map((event) => (
                <li
                  key={event.id}
                  className="flex flex-col gap-2 py-5 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0">
                    <p className="font-body text-base text-ink">
                      {auditEventLabel(event.eventType)}
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

            {loadMoreError ? (
              <div
                role="alert"
                className="mt-6 border border-accent-soft/40 bg-surface px-5 py-6"
              >
                <p className="type-subheading text-accent-soft">
                  Could not load more activity
                </p>
                <p className="type-body mt-2 text-ink-secondary">
                  {loadMoreError}
                </p>
                <Button
                  variant="secondary"
                  onClick={() => void loadMore()}
                  disabled={isLoadingMore}
                  className="mt-6"
                >
                  Try again
                </Button>
              </div>
            ) : null}

            {hasMore && !loadMoreError ? (
              <div className="mt-6 flex justify-center">
                <Button
                  variant="secondary"
                  onClick={() => void loadMore()}
                  isLoading={isLoadingMore}
                  disabled={isLoadingMore}
                >
                  {isLoadingMore ? 'Loading more…' : 'Load more'}
                </Button>
              </div>
            ) : null}
          </>
        ) : null}
      </Section>
    </PageContainer>
  )
}
