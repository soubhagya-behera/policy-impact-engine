import { afterEach, describe, expect, it, vi } from 'vitest'
import { registerAuthBridge } from './client'
import { getRecommendationDetail } from './recommendations'

/**
 * Recommendation detail request contract.
 *
 * Single persisted recommendation plus its assessment navigation
 * (`policyId`, `versionNumber`). Travels the authenticated path with the
 * Bearer token attached; unknown or foreign ids answer `404`.
 */
function stubBridge() {
  registerAuthBridge({
    getAccessToken: () => 'test-access-token',
    getRefreshToken: () => null,
    hasExpiredAccessToken: () => false,
    refresh: () => Promise.resolve(null),
    invalidate: () => undefined,
  })
}

describe('getRecommendationDetail', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    registerAuthBridge(null)
  })

  it('requests one recommendation by exact URL with authentication', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
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
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const detail = await getRecommendationDetail('rec-1')

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url.endsWith('/api/v1/me/recommendations/rec-1')).toBe(true)
    expect(init.method ?? 'GET').toBe('GET')
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
    expect(detail).toMatchObject({
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
    })
  })

  it('maps a nullable conceptCode for the NONE_REQUIRED closure row', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            id: 'rec-closure',
            assessmentId: 'assessment-1',
            policyId: 'policy-1',
            versionNumber: 2,
            ruleId: 'REC-NONE-REQUIRED',
            ruleOrder: 4,
            actionKind: 'NONE_REQUIRED',
            conceptCode: null,
            personalizedNormalized: 5,
            personalizedBand: 'NONE',
            recommendationRulesVersion: 1,
            createdAt: '2026-10-07T10:00:00Z',
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const detail = await getRecommendationDetail('rec-closure')

    expect(detail.conceptCode).toBeNull()
    expect(detail.actionKind).toBe('NONE_REQUIRED')
  })

  it('surfaces an unknown recommendation as a 404 failure', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            title: 'Not Found',
            detail: 'Recommendation not found',
          }),
          {
            status: 404,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(getRecommendationDetail('missing-id')).rejects.toMatchObject({
      status: 404,
    })
  })
})
