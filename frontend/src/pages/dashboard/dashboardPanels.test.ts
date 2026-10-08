import { describe, expect, it } from 'vitest'
import { formatRelativeTime } from '../../lib/format'
import { recommendationMetaLine } from './DashboardPanels'

/**
 * Recommendation row secondary line.
 *
 * A normal row names its concept (`CONCEPT · age`). The
 * assessment-level `NONE_REQUIRED` closure row carries
 * `conceptCode: null` and belongs to no single concept, so its line
 * must never render a literal "null" — it keeps the relative age on
 * its own while the headline already reads "No action required".
 */
describe('recommendationMetaLine', () => {
  it('renders the concept and age for a normal recommendation', () => {
    const createdAt = '2026-10-07T10:00:00Z'

    const line = recommendationMetaLine('THIRD_PARTY_SHARING', createdAt)

    expect(line).toContain('THIRD_PARTY_SHARING')
    expect(line).toContain('·')
    expect(line).toBe(`THIRD_PARTY_SHARING · ${formatRelativeTime(createdAt)}`)
  })

  it('renders only the age without a literal null for a null conceptCode', () => {
    const createdAt = '2026-10-07T10:00:00Z'

    const line = recommendationMetaLine(null, createdAt)

    expect(line).not.toContain('null')
    expect(line).toBe(formatRelativeTime(createdAt))
  })
})
