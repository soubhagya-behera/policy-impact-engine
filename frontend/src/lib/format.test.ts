import { describe, expect, it } from 'vitest'
import type { ImpactSummary } from '../api/types'
import { formatBytes, formatDurationMs, maxImpactScoreDisplay } from './format'

/**
 * The backend reports `maxAggregateScore: 0` for a user with no
 * assessments — a non-measurement, not a real score. The headline
 * figure must render an em dash in that case rather than a
 * misleading zero, while a genuine measured value (including a real
 * zero from existing assessments) renders verbatim.
 */
describe('maxImpactScoreDisplay', () => {
  function summaryWith(
    overrides: Partial<ImpactSummary> = {},
  ): ImpactSummary {
    return {
      totalAssessments: 0,
      assessmentsByBand: [],
      maxAggregateScore: 0,
      actionableRecommendations: 0,
      latest: null,
      ...overrides,
    }
  }

  it('renders an em dash when there is no summary yet', () => {
    expect(maxImpactScoreDisplay(null)).toBe('—')
    expect(maxImpactScoreDisplay(undefined)).toBe('—')
  })

  it('renders an em dash when no assessments exist', () => {
    expect(maxImpactScoreDisplay(summaryWith())).toBe('—')
    expect(
      maxImpactScoreDisplay(
        summaryWith({ totalAssessments: 0, maxAggregateScore: 0 }),
      ),
    ).toBe('—')
  })

  it('renders the measured score once assessments exist', () => {
    expect(
      maxImpactScoreDisplay(
        summaryWith({ totalAssessments: 3, maxAggregateScore: 78 }),
      ),
    ).toBe(78)
  })

  it('renders a genuine measured zero when assessments exist', () => {
    expect(
      maxImpactScoreDisplay(
        summaryWith({ totalAssessments: 2, maxAggregateScore: 0 }),
      ),
    ).toBe(0)
  })
})

describe('formatBytes', () => {
  it('renders a dash when nothing was measured', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
  })

  it('renders measured byte counts', () => {
    expect(formatBytes(0)).toBe('0 bytes')
    expect(formatBytes(1234567)).toBe('1,234,567 bytes')
  })
})

describe('formatDurationMs', () => {
  it('renders a dash while still running', () => {
    expect(formatDurationMs(null)).toBe('—')
    expect(formatDurationMs(undefined)).toBe('—')
  })

  it('renders measured durations', () => {
    expect(formatDurationMs(0)).toBe('0 ms')
    expect(formatDurationMs(1500)).toBe('1,500 ms')
  })
})
