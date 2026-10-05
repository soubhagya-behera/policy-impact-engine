import { listNotifications } from '../../api/notifications'
import { listRecommendations } from '../../api/recommendations'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Section } from '../../components/ui/Layout'
import { StatusChip } from '../../components/ui/StatusChip'
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
            <li
              key={item.id}
              className="flex flex-col gap-2 py-5 sm:flex-row sm:items-center sm:justify-between"
            >
              <div className="min-w-0">
                <p className="font-body text-base text-ink">
                  {actionKindLabel(item.actionKind)}
                </p>
                <p className="mt-1 font-body text-sm text-ink-ghost">
                  {item.conceptCode} · {formatRelativeTime(item.createdAt)}
                </p>
              </div>
              <StatusChip
                label={bandLabel(item.personalizedBand)}
                tone={
                  item.personalizedBand === 'NONE' ? 'neutral' : 'accent'
                }
                className={bandColor(item.personalizedBand)}
              />
            </li>
          ))}
        </ul>
      ) : null}
    </Section>
  )
}

export function NotificationsPanel() {
  const state = useAsyncData<Notification[]>(
    (signal) => listNotifications({ page: 0, size: 5 }, signal),
    (data) => data.length === 0,
  )

  const unreadCount = state.data?.filter((item) => !item.read).length ?? 0

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

      {state.data && state.data.length > 0 ? (
        <ul className="divide-y divide-line border-y border-line">
          {state.data.map((item) => (
            <li
              key={item.id}
              className="flex flex-col gap-2 py-5 sm:flex-row sm:items-center sm:justify-between"
            >
              <div className="min-w-0">
                <p className="font-body text-base text-ink">
                  Version {item.versionNumber} detected
                </p>
                <p className="mt-1 font-body text-sm text-ink-ghost">
                  {formatRelativeTime(item.createdAt)}
                </p>
              </div>
              <StatusChip
                label={item.read ? 'Read' : 'New'}
                tone={item.read ? 'neutral' : 'positive'}
              />
            </li>
          ))}
        </ul>
      ) : null}
    </Section>
  )
}
