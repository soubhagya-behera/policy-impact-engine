import { Link } from 'react-router-dom'
import { Button, LinkButton } from '../../components/ui/Button'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Section } from '../../components/ui/Layout'
import { StatusChip } from '../../components/ui/StatusChip'
import { policyDetailPath, ROUTES } from '../../app/routes'
import { displayUrl, formatRelativeTime, statusLabel } from '../../lib/format'
import type { AsyncState } from '../../hooks/useAsyncData'
import type { Policy } from '../../api/types'

/** Preview of the tracked policies, linking through to the policies page. */
export function PoliciesSection({ state }: { state: AsyncState<Policy[]> }) {
  const policies = state.data ?? []
  const preview = policies.slice(0, 5)

  return (
    <Section
      title="Your policies"
      description="The documents this engine is currently watching."
      actions={
        <Button
          variant="secondary"
          onClick={state.reload}
          isLoading={state.status === 'loading'}
        >
          Refresh
        </Button>
      }
    >
      {state.isInitialLoading ? <SkeletonRows rows={3} /> : null}

      {state.status === 'error' && state.errorMessage ? (
        <ErrorState message={state.errorMessage} onRetry={state.reload} />
      ) : null}

      {state.isEmpty ? (
        <EmptyState
          title="No policies yet"
          description="Add the URL of a policy document you want to track and the engine will watch it for changes."
          action={<LinkButton to={ROUTES.policies}>Add a policy</LinkButton>}
        />
      ) : null}

      {preview.length > 0 ? (
        <ul className="divide-y divide-line border-y border-line">
          {preview.map((policy) => (
            <li key={policy.id} className="py-5">
              <Link
                to={policyDetailPath(policy.id)}
                className="flex flex-col gap-3 py-1 transition-colors duration-150 ease-standard sm:flex-row sm:items-center sm:justify-between"
              >
                <span className="min-w-0">
                  <span className="block truncate font-body text-base text-ink">
                    {policy.name}
                  </span>
                  <span className="mt-1 block truncate font-body text-sm text-ink-ghost">
                    {displayUrl(policy.url)}
                  </span>
                </span>
                <span className="flex shrink-0 items-center gap-4">
                  <span className="font-body text-sm text-ink-ghost">
                    {formatRelativeTime(policy.updatedAt)}
                  </span>
                  <StatusChip
                    label={statusLabel(policy.status)}
                    tone={policy.status === 'ACTIVE' ? 'positive' : 'neutral'}
                  />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
    </Section>
  )
}
