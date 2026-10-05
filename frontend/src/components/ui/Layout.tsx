import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'

/**
 * Page-level heading block. The label + rule above the title reproduces the
 * editorial section treatment of the reference rather than a card header.
 */
export function PageHeader({
  label,
  title,
  description,
  actions,
}: {
  label: string
  title: string
  description?: string
  actions?: ReactNode
}) {
  return (
    <header className="border-b border-line pb-10">
      <p className="type-label text-accent-soft">{label}</p>
      <div className="mt-5 flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
        <div className="max-w-3xl">
          <h1 className="type-section text-ink">{title}</h1>
          {description ? (
            <p className="type-body mt-5 max-w-prose text-ink-muted">
              {description}
            </p>
          ) : null}
        </div>
        {actions ? (
          <div className="flex shrink-0 flex-wrap gap-3">{actions}</div>
        ) : null}
      </div>
    </header>
  )
}

/**
 * Bordered content section. `divider` adds the hairline above the section so
 * stacked sections read as an editorial column rather than stacked cards.
 */
export function Section({
  title,
  description,
  children,
  actions,
  className,
}: {
  title: string
  description?: string
  children: ReactNode
  actions?: ReactNode
  className?: string
}) {
  return (
    <section className={cn('py-10', className)}>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="type-subheading text-ink">{title}</h2>
          {description ? (
            <p className="type-body mt-2 max-w-prose text-ink-muted">
              {description}
            </p>
          ) : null}
        </div>
        {actions ? <div className="shrink-0">{actions}</div> : null}
      </div>
      <div className="mt-6">{children}</div>
    </section>
  )
}

/**
 * Large numeric figure. Numbers are tabular so a changing value does not
 * reflow the label beside it.
 */
export function StatFigure({
  value,
  label,
  tone = 'neutral',
}: {
  value: ReactNode
  label: string
  tone?: 'neutral' | 'positive' | 'accent' | 'info'
}) {
  const toneClass =
    tone === 'positive'
      ? 'text-positive'
      : tone === 'accent'
        ? 'text-accent-soft'
        : tone === 'info'
          ? 'text-info-soft'
          : 'text-ink'

  return (
    <div className="border-t border-line pt-5">
      <p className={cn('type-section tabular-nums', toneClass)}>{value}</p>
      <p className="type-label mt-3 text-ink-faint">{label}</p>
    </div>
  )
}
