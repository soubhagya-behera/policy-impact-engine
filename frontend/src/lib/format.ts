import type {
  ImpactBand,
  ImpactSummary,
  PolicyStatus,
} from '../api/types'

/**
 * Presentation helpers shared across pages. Kept out of components so band
 * and status wording is defined exactly once.
 */

/** Human label for an impact band, e.g. `CRITICAL` -> `Critical`. */
export function bandLabel(band: ImpactBand): string {
  return band.charAt(0) + band.slice(1).toLowerCase()
}

/** Tailwind text colour per impact band, using the supplied accent palette. */
export function bandColor(band: ImpactBand): string {
  switch (band) {
    case 'CRITICAL':
      return 'text-accent-soft'
    case 'HIGH':
      return 'text-accent-mid'
    case 'MEDIUM':
      return 'text-info-soft'
    case 'LOW':
      return 'text-positive'
    case 'NONE':
    default:
      return 'text-ink-faint'
  }
}

/** Guidance sentence per band, so a score is always explained. */
export function bandDescription(band: ImpactBand): string {
  switch (band) {
    case 'CRITICAL':
      return 'Substantial privacy change. Acting on this is recommended.'
    case 'HIGH':
      return 'Significant privacy change worth reviewing.'
    case 'MEDIUM':
      return 'Moderate privacy change. Worth a look when you have time.'
    case 'LOW':
      return 'Small privacy change detected.'
    case 'NONE':
    default:
      return 'No meaningful privacy impact detected.'
  }
}

export function statusLabel(status: PolicyStatus): string {
  return status === 'ACTIVE' ? 'Active' : 'Archived'
}

/** Human label for a persisted change type, e.g. `MODIFIED` -> `Modified`. */
export function changeTypeLabel(changeType: string): string {
  switch (changeType) {
    case 'ADDED':
      return 'Added'
    case 'REMOVED':
      return 'Removed'
    case 'MODIFIED':
      return 'Modified'
    default:
      return changeType
  }
}

/** Tailwind text colour per policy status. */
export function statusColor(status: PolicyStatus): string {
  return status === 'ACTIVE' ? 'text-positive' : 'text-ink-faint'
}

/** Turns an unknown action kind into readable prose. */
export function actionKindLabel(actionKind: string): string {
  switch (actionKind) {
    case 'EXERCISE_DELETION':
      return 'Consider deleting your data'
    case 'OPT_OUT_SHARING':
      return 'Opt out of data sharing'
    case 'REVIEW_SETTINGS':
      return 'Review your settings'
    case 'NONE_REQUIRED':
    default:
      return 'No action required'
  }
}

/**
 * Absolute date/time in the viewer's locale. Rendered inside `<time>` with a
 * machine-readable `dateTime` attribute supplied by the caller.
 */
export function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Short relative age, e.g. `3 days ago`. */
export function formatRelativeTime(iso: string | null): string {
  if (!iso) return '—'
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return '—'

  const diffSeconds = Math.round((Date.now() - parsed.getTime()) / 1000)
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 31_536_000],
    ['month', 2_592_000],
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ]

  const formatter = new Intl.RelativeTimeFormat(undefined, {
    numeric: 'auto',
  })
  for (const [unit, seconds] of units) {
    if (Math.abs(diffSeconds) >= seconds) {
      return formatter.format(-Math.round(diffSeconds / seconds), unit)
    }
  }
  return formatter.format(-diffSeconds, 'second')
}

/** Strips the scheme for display so a long URL never overflows its cell. */
export function displayUrl(url: string): string {
  return url.replace(/^https?:\/\//, '').replace(/\/$/, '')
}

/** Byte counts for fetch attempts; null (never measured) renders as a dash. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—'
  return `${bytes.toLocaleString('en-US')} bytes`
}

/** Attempt durations; null (still running) renders as a dash. */
export function formatDurationMs(durationMs: number | null | undefined): string {
  if (durationMs === null || durationMs === undefined) return '—'
  return `${durationMs.toLocaleString('en-US')} ms`
}

/**
 * Highest measured aggregate score for the headline figure.
 *
 * The backend reports `maxAggregateScore: 0` for a user with no
 * assessments at all — a non-measurement, not a real score — so an
 * empty summary (or no summary yet) renders as an em dash rather than
 * a misleading zero. A genuine measured zero (assessments exist) still
 * renders as `0`.
 */
export function maxImpactScoreDisplay(
  summary: ImpactSummary | null | undefined,
): number | string {
  if (!summary || summary.totalAssessments === 0) return '—'
  return summary.maxAggregateScore
}
