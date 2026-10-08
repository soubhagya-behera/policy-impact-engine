import { Link } from 'react-router-dom'
import { useState } from 'react'
import { toErrorMessage } from '../../api/errors'
import { listNotifications, markNotificationRead } from '../../api/notifications'
import { listRecommendations } from '../../api/recommendations'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Button } from '../../components/ui/Button'
import { Section } from '../../components/ui/Layout'
import { StatusChip } from '../../components/ui/StatusChip'
import { policyDetailPath, recommendationDetailPath } from '../../app/routes'
import { useAsyncData } from '../../hooks/useAsyncData'
import {
  actionKindLabel,
  bandLabel,
  bandColor,
  formatRelativeTime,
} from '../../lib/format'
import type { Notification, RecommendationSummary } from '../../api/types'

/**
 * Dashboard panels backed by real endpoints.
 *
 * Each panel owns its own request so one slow or failing endpoint degrades
 * only that panel instead of blanking the whole overview.
 *
 * The `AbortSignal` from the hook is forwarded to each request so navigating
 * away cancels in-flight work rather than resolving into an unmounted tree.
 */

/**
 * Secondary line under a recommendation row: `CONCEPT · age`.
 *
 * `conceptCode` is `null` only for the assessment-level `NONE_REQUIRED`
 * closure row, which belongs to no single concept — rendering it directly
 * would print a literal "null". The closure row keeps the relative age on
 * its own; its headline already reads "No action required".
 */
export function recommendationMetaLine(
  conceptCode: string | null,
  createdAt: string,
): string {
  const age = formatRelativeTime(createdAt)
  return conceptCode ? `${conceptCode} · ${age}` : age
}

export function RecommendationsPanel() {
  const state = useAsyncData<RecommendationSummary[]>(
    (signal) => listRecommendations({ page: 0, size: 5 }, signal),
    (data) => data.length === 0,
  )

  return (
    <Section
      title="Recommended actions"
      description="Personalised to the privacy concepts you care about most."
    >
      {state.isInitialLoading ? <SkeletonRows rows={2} /> : null}

      {state.status === 'error' && state.errorMessage ? (
        <ErrorState message={state.errorMessage} onRetry={state.reload} />
      ) : null}

      {state.isEmpty ? (
        <EmptyState
          title="Nothing needs your attention"
          description="When a tracked policy changes in a way that affects you, the recommended action appears here."
        />
      ) : null}

      {state.data && state.data.length > 0 ? (
        <ul className="divide-y divide-line border-y border-line">
          {state.data.map((item) => (
            <li key={item.id} className="py-5">
              <Link
                to={recommendationDetailPath(item.id)}
                className="flex flex-col gap-2 py-1 transition-colors duration-150 ease-standard sm:flex-row sm:items-center sm:justify-between"
              >
                <div className="min-w-0">
                  <p className="font-body text-base text-ink">
                    {actionKindLabel(item.actionKind)}
                  </p>
                  <p className="mt-1 font-body text-sm text-ink-ghost">
                    {recommendationMetaLine(item.conceptCode, item.createdAt)}
                  </p>
                </div>
                <StatusChip
                  label={bandLabel(item.personalizedBand)}
                  tone={
                    item.personalizedBand === 'NONE' ? 'neutral' : 'accent'
                  }
                  className={bandColor(item.personalizedBand)}
                />
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
    </Section>
  )
}

/**
 * Whether a notification row offers the mark-read action: unread rows
 * only. Read rows never show it.
 */
export function canMarkNotificationRead(notification: Notification): boolean {
  return !notification.read
}

/**
 * User-facing message for a failed mark-read. Surfaces the backend's
 * own message verbatim, including connectivity guidance for network
 * failures.
 */
export function toMarkReadError(error: unknown): string {
  return toErrorMessage(error)
}

/**
 * Marks one notification read, then reloads the feed so the row flips
 * to `Read`. The reload runs only after the POST succeeds; failures
 * propagate to the caller for display and never trigger a reload.
 */
export async function markReadAndReload(
  notificationId: string,
  reload: () => void,
): Promise<void> {
  await markNotificationRead(notificationId)
  reload()
}

export function NotificationsPanel() {
  const state = useAsyncData<Notification[]>(
    (signal) => listNotifications({ page: 0, size: 5 }, signal),
    (data) => data.length === 0,
  )

  const unreadCount = state.data?.filter((item) => !item.read).length ?? 0
  const [pendingIds, setPendingIds] = useState<string[]>([])
  const [markFailure, setMarkFailure] = useState<{
    notificationId: string
    message: string
  } | null>(null)

  async function handleMarkRead(notificationId: string): Promise<void> {
    if (pendingIds.includes(notificationId)) return
    setPendingIds((ids) => [...ids, notificationId])
    setMarkFailure(null)
    try {
      await markReadAndReload(notificationId, state.reload)
    } catch (error) {
      setMarkFailure({
        notificationId,
        message: toMarkReadError(error),
      })
    } finally {
      setPendingIds((ids) => ids.filter((id) => id !== notificationId))
    }
  }

  return (
    <Section
      title="Recent activity"
      description="Policy changes detected for the policies you track."
      actions={
        unreadCount > 0 ? (
          <StatusChip label={`${unreadCount} unread`} tone="info" />
        ) : null
      }
    >
      {state.isInitialLoading ? <SkeletonRows rows={2} /> : null}

      {state.status === 'error' && state.errorMessage ? (
        <ErrorState message={state.errorMessage} onRetry={state.reload} />
      ) : null}

      {state.isEmpty ? (
        <EmptyState
          title="No changes detected yet"
          description="Once a tracked policy is checked and a new version is observed, it will show up here."
        />
      ) : null}

      {markFailure ? (
        <ErrorState
          message={markFailure.message}
          onRetry={() => void handleMarkRead(markFailure.notificationId)}
        />
      ) : null}

      {state.data && state.data.length > 0 ? (
        <ul className="divide-y divide-line border-y border-line">
          {state.data.map((item) => (
            <li
              key={item.id}
              className="flex flex-col gap-2 py-5 sm:flex-row sm:items-center sm:justify-between"
            >
              <Link
                to={policyDetailPath(item.policyId)}
                className="min-w-0 flex-1 py-1 transition-colors duration-150 ease-standard"
              >
                <p className="font-body text-base text-ink">
                  Version {item.versionNumber} detected
                </p>
                <p className="mt-1 font-body text-sm text-ink-ghost">
                  {formatRelativeTime(item.createdAt)}
                </p>
              </Link>
              <div className="flex items-center gap-3">
                <StatusChip
                  label={item.read ? 'Read' : 'New'}
                  tone={item.read ? 'neutral' : 'positive'}
                />
                {canMarkNotificationRead(item) ? (
                  <Button
                    variant="ghost"
                    isLoading={pendingIds.includes(item.id)}
                    onClick={() => void handleMarkRead(item.id)}
                  >
                    Mark as read
                  </Button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      ) : null}
    </Section>
  )
}
