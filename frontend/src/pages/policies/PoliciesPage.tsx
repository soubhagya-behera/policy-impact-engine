import { useState } from 'react'
import { Link } from 'react-router-dom'
import { listPolicies } from '../../api/policies'
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
import { displayUrl, formatDateTime, statusLabel } from '../../lib/format'
import type { Policy } from '../../api/types'
import { policyDetailPath } from '../../app/routes'
import { AddPolicyForm } from './AddPolicyForm'
import { PolicyTable } from './PolicyTable'

/**
 * Policy list, sourced from `GET /api/v1/policies`.
 *
 * The add-policy form is revealed on demand so the default view is a clean
 * editorial column rather than a permanently open form.
 */
export function PoliciesPage() {
  const [isFormOpen, setIsFormOpen] = useState(false)

  const policies = useAsyncData<Policy[]>(
    (signal) => listPolicies({ page: 0, size: 50 }, signal),
    (data) => data.length === 0,
  )

  return (
    <PageContainer>
      <PageHeader
        label="Policies"
        title="Tracked policy documents"
        description="Every URL this engine watches. Each document is checked on a schedule and its changes are assessed for privacy impact."
        actions={
          <Button
            variant={isFormOpen ? 'secondary' : 'primary'}
            onClick={() => setIsFormOpen((open) => !open)}
          >
            {isFormOpen ? 'Close form' : 'Add Policy'}
          </Button>
        }
      />

      {isFormOpen ? (
        <section className="border-b border-line py-10">
          <h2 className="type-subheading text-ink">Add a policy</h2>
          <p className="type-body mt-2 text-ink-muted">
            Give the engine a document URL to watch.
          </p>
          <div className="mt-8">
            <AddPolicyForm
              onCreated={() => {
                policies.reload()
                setIsFormOpen(false)
              }}
            />
          </div>
        </section>
      ) : null}

      <Section
        title="Your policies"
        description={
          policies.data
            ? `${policies.data.length} ${policies.data.length === 1 ? 'policy' : 'policies'} tracked`
            : 'Loading your tracked policies.'
        }
        actions={
          <Button
            variant="secondary"
            onClick={policies.reload}
            isLoading={policies.status === 'loading'}
          >
            Refresh
          </Button>
        }
      >
        {policies.isInitialLoading ? <SkeletonRows rows={3} /> : null}

        {policies.status === 'error' && policies.errorMessage ? (
          <ErrorState
            message={policies.errorMessage}
            onRetry={policies.reload}
          />
        ) : null}

        {policies.isEmpty ? (
          <EmptyState
            title="No policies tracked yet"
            description="Add the URL of a policy document you want to track. The engine will watch it for changes and assess the privacy impact of each new version."
            action={
              <Button onClick={() => setIsFormOpen(true)}>Add your first policy</Button>
            }
          />
        ) : null}

        {policies.data && policies.data.length > 0 ? (
          <>
            {/* Table at desktop widths */}
            <div className="hidden md:block">
              <PolicyTable policies={policies.data} />
            </div>
            {/* Stacked rows on mobile: no horizontal scrolling required */}
            <ul className="divide-y divide-line border-y border-line md:hidden">
              {policies.data.map((policy) => (
                <li key={policy.id} className="py-5">
                  <Link
                    to={policyDetailPath(policy.id)}
                    className="block py-1 transition-colors duration-150 ease-standard"
                  >
                    <span className="flex items-start justify-between gap-4">
                      <span className="min-w-0">
                        <span className="block truncate font-body text-base text-ink">
                          {policy.name}
                        </span>
                        <span className="mt-1 block truncate font-body text-sm text-ink-ghost">
                          {displayUrl(policy.url)}
                        </span>
                      </span>
                      <StatusChip
                        label={statusLabel(policy.status)}
                        tone={
                          policy.status === 'ACTIVE' ? 'positive' : 'neutral'
                        }
                      />
                    </span>
                    <span className="mt-3 block font-body text-sm text-ink-ghost">
                      Updated {formatDateTime(policy.updatedAt)}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </Section>
    </PageContainer>
  )
}
