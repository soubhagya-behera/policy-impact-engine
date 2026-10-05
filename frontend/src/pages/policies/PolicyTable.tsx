import { Link } from 'react-router-dom'
import { StatusChip } from '../../components/ui/StatusChip'
import { displayUrl, formatDateTime, statusLabel } from '../../lib/format'
import { policyDetailPath } from '../../app/routes'
import type { Policy } from '../../api/types'

/**
 * Desktop table view of the policy list.
 *
 * Uses a real `<table>` so column relationships are conveyed structurally.
 * URLs are truncated with CSS rather than by rewriting the link, so the full
 * address remains available to assistive tech and on hover.
 */
export function PolicyTable({ policies }: { policies: Policy[] }) {
  return (
    <table className="w-full table-fixed border-collapse text-left">
      <caption className="sr-only">
        Tracked policy documents with status and last update time
      </caption>
      <thead>
        <tr className="border-y border-line">
          <th scope="col" className="type-label py-4 pr-6 font-normal text-ink-faint">
            Policy
          </th>
          <th scope="col" className="type-label w-[28%] py-4 pr-6 font-normal text-ink-faint">
            Status
          </th>
          <th scope="col" className="type-label w-[22%] py-4 font-normal text-ink-faint">
            Updated
          </th>
        </tr>
      </thead>
      <tbody>
        {policies.map((policy) => (
          <tr key={policy.id} className="border-b border-line">
            <td className="py-5 pr-6 align-top">
              <Link
                to={policyDetailPath(policy.id)}
                className="block transition-colors duration-150 ease-standard hover:text-accent-soft"
              >
                <span className="block truncate font-body text-base text-ink">
                  {policy.name}
                </span>
                <span
                  className="mt-1 block truncate font-body text-sm text-ink-ghost"
                  title={policy.url}
                >
                  {displayUrl(policy.url)}
                </span>
              </Link>
            </td>
            <td className="py-5 pr-6 align-top">
              <StatusChip
                label={statusLabel(policy.status)}
                tone={policy.status === 'ACTIVE' ? 'positive' : 'neutral'}
              />
            </td>
            <td className="py-5 align-top">
              <span className="font-body text-sm text-ink-muted">
                <time dateTime={policy.updatedAt}>
                  {formatDateTime(policy.updatedAt)}
                </time>
              </span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
