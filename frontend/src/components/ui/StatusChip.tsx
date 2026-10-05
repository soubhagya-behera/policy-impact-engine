import { cn } from '../../lib/cn'

/**
 * Small status marker. Uses a tinted square plus label rather than a filled
 * pill, keeping the flat, unrounded language of the design system.
 */
export interface StatusChipProps {
  label: string
  /** Tailwind text colour class from the token set. */
  tone?: 'neutral' | 'positive' | 'accent' | 'info'
  className?: string
}

const TONE_CLASSES: Record<NonNullable<StatusChipProps['tone']>, string> = {
  neutral: 'text-ink-faint border-line-strong',
  positive: 'text-positive border-positive/40',
  accent: 'text-accent-soft border-accent-soft/40',
  info: 'text-info-soft border-info-soft/40',
}

const DOT_CLASSES: Record<NonNullable<StatusChipProps['tone']>, string> = {
  neutral: 'bg-ink-ghost',
  positive: 'bg-positive',
  accent: 'bg-accent-soft',
  info: 'bg-info-soft',
}

export function StatusChip({
  label,
  tone = 'neutral',
  className,
}: StatusChipProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 border px-2.5 py-1',
        'font-body text-xs uppercase tracking-[0.08em]',
        TONE_CLASSES[tone],
        className,
      )}
    >
      <span
        aria-hidden="true"
        className={cn('size-1.5 shrink-0', DOT_CLASSES[tone])}
      />
      {label}
    </span>
  )
}
