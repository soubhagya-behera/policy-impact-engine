import type { ReactNode } from 'react'
import { Button } from './Button'

/**
 * Shared loading / empty / error presentations.
 *
 * These exist so every panel fails, empties, and reports identically. Each is
 * a live region so assistive technology announces state changes.
 */

/** Indeterminate wait. Announced politely, never focusable. */
export function LoadingState({ label }: { label: string }) {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-busy="true"
      className="flex items-center gap-3 py-10 text-ink-faint"
    >
      <span
        aria-hidden="true"
        className="block h-2 w-2 animate-pulse bg-accent-soft"
        style={{ animationDuration: '300ms' }}
      />
      <span className="type-label">{label}</span>
    </div>
  )
}

/**
 * Placeholder rows for a list that is loading. Uses the same hairline rhythm
 * as the real rows, so the layout does not jump when data arrives.
 */
export function SkeletonRows({ rows = 3 }: { rows?: number }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true">
      <span className="sr-only">Loading</span>
      <div className="divide-y divide-line border-y border-line">
        {Array.from({ length: rows }, (_, index) => (
          <div key={index} className="flex flex-col gap-2 py-5">
            <div className="h-4 w-2/5 bg-surface" />
            <div className="h-3 w-3/5 bg-surface" />
          </div>
        ))}
      </div>
    </div>
  )
}

/** Legitimate "nothing here yet" state — never used for a failure. */
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="border-y border-line py-12">
      <p className="type-subheading text-ink">{title}</p>
      <p className="type-body mt-2 max-w-prose text-ink-muted">{description}</p>
      {action ? <div className="mt-6">{action}</div> : null}
    </div>
  )
}

/** Failure state with an optional retry. Errors are never shown as empty. */
export function ErrorState({
  title = 'Something went wrong',
  message,
  onRetry,
}: {
  title?: string
  message: string
  onRetry?: () => void
}) {
  return (
    <div
      role="alert"
      className="border border-accent-soft/40 bg-surface px-5 py-6"
    >
      <p className="type-subheading text-accent-soft">{title}</p>
      <p className="type-body mt-2 text-ink-secondary">{message}</p>
      {onRetry ? (
        <Button
          variant="secondary"
          onClick={onRetry}
          className="mt-6"
        >
          Try again
        </Button>
      ) : null}
    </div>
  )
}
