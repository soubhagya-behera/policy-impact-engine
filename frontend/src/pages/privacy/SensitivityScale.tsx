import { cn } from '../../lib/cn'

/** Backend sensitivity range is 0-5 inclusive. */
const LEVELS = [0, 1, 2, 3, 4, 5] as const

/**
 * Bar heights per level. Declared as literal class names (not interpolated)
 * so Tailwind's static extractor can see them in the built CSS.
 */
const LEVEL_HEIGHT: Record<(typeof LEVELS)[number], string> = {
  0: 'h-1.5',
  1: 'h-3',
  2: 'h-4',
  3: 'h-5',
  4: 'h-6',
  5: 'h-7',
}

/**
 * Read-only visualisation of a 0-5 sensitivity value.
 *
 * Rendered as a bar chart with a text equivalent rather than colour alone, so
 * the level is not conveyed by hue: each filled bar corresponds to one step,
 * bar height rises with the level, and the value is written out for screen
 * readers.
 */
export function SensitivityScale({ value }: { value: number }) {
  const clamped = Math.max(0, Math.min(5, Math.round(value)))
  const descriptor =
    clamped === 0
      ? 'Not sensitive'
      : clamped <= 2
        ? 'Low sensitivity'
        : clamped <= 4
          ? 'Medium sensitivity'
          : 'High sensitivity'

  return (
    <div className="flex shrink-0 items-center gap-4">
      <span
        className="flex items-end gap-1"
        role="img"
        aria-label={`Sensitivity ${clamped} of 5: ${descriptor}`}
      >
        {LEVELS.map((level) => (
          <span
            key={level}
            aria-hidden="true"
            className={cn(
              'block w-1.5',
              LEVEL_HEIGHT[level],
              level < clamped ? 'bg-accent-soft' : 'bg-line-strong',
            )}
          />
        ))}
      </span>
      <span className="font-body text-sm tabular-nums text-ink-muted">
        {clamped}/5
      </span>
    </div>
  )
}
