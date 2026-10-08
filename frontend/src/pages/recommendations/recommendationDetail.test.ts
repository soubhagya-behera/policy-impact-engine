import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '../../api/errors'
import type { RecommendationDetail } from '../../api/types'
import {
  toRecommendationError,
  toRecommendationFacts,
} from './RecommendationDetailPage'

/**
 * Recommendation-detail mapping.
 *
 * A `404` means the recommendation is gone; every other failure
 * surfaces the backend's own message, including network errors. The
 * display facts mirror the backend DTO field-for-field, and a null
 * `conceptCode` (the assessment-level `NONE_REQUIRED` closure row)
 * passes through as null so the view omits the concept instead of
 * printing a literal "null".
 */
describe('toRecommendationFacts', () => {
  function detailWith(
    overrides: Partial<RecommendationDetail> = {},
  ): RecommendationDetail {
    return {
      id: 'rec-1',
      assessmentId: 'assessment-1',
      policyId: 'policy-1',
      versionNumber: 2,
      ruleId: 'REC-SHARING-OPT-OUT',
      ruleOrder: 2,
      actionKind: 'OPT_OUT_SHARING',
      conceptCode: 'THIRD_PARTY_SHARING',
      personalizedNormalized: 72,
      personalizedBand: 'HIGH',
      recommendationRulesVersion: 1,
      createdAt: '2026-10-07T10:00:00Z',
      ...overrides,
    }
  }

  it('maps every backend DTO field to its display fact', () => {
    const facts = toRecommendationFacts(detailWith())

    expect(facts.headline).toBe('Opt out of data sharing')
    expect(facts.conceptCode).toBe('THIRD_PARTY_SHARING')
    expect(facts.score).toBe(72)
    expect(facts.band).toBe('HIGH')
    expect(facts.bandDisplay).toBe('High')
    expect(facts.ruleId).toBe('REC-SHARING-OPT-OUT')
    expect(facts.ruleOrder).toBe(2)
    expect(facts.rulesVersion).toBe(1)
    expect(facts.assessedValue).toBe('2026-10-07T10:00:00Z')
    expect(facts.assessedDisplay).toContain('2026')
    expect(facts.policyPath).toBe('/app/policies/policy-1')
    expect(facts.versionDisplay).toBe('Version 2')
    expect(facts.assessmentId).toBe('assessment-1')
  })

  it('keeps a null conceptCode for the NONE_REQUIRED closure row', () => {
    const facts = toRecommendationFacts(
      detailWith({
        ruleId: 'REC-NONE-REQUIRED',
        ruleOrder: 4,
        actionKind: 'NONE_REQUIRED',
        conceptCode: null,
        personalizedNormalized: 5,
        personalizedBand: 'NONE',
      }),
    )

    expect(facts.conceptCode).toBeNull()
    expect(facts.headline).toBe('No action required')
    expect(facts.band).toBe('NONE')
  })
})

describe('toRecommendationError', () => {
  it('maps a missing recommendation to a plain explanation', () => {
    expect(
      toRecommendationError(
        new ApiError(404, '/api/v1/me/recommendations/missing', {
          title: 'Not Found',
          detail: 'Recommendation not found',
        }),
      ),
    ).toContain('no longer exists')
  })

  it('surfaces other backend failures verbatim', () => {
    expect(
      toRecommendationError(
        new ApiError(500, '/api/v1/me/recommendations/rec-1', {
          title: 'Internal Server Error',
          detail: 'Database unavailable',
        }),
      ),
    ).toContain('Database unavailable')
  })

  it('surfaces network failures as connectivity guidance', () => {
    expect(toRecommendationError(new NetworkError('down'))).toContain(
      'Could not reach the server',
    )
  })
})
