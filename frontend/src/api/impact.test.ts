import { afterEach, describe, expect, it, vi } from 'vitest'
import { registerAuthBridge } from './client'
import { getImpactSummary, listImpactAssessments } from './impact'

/**
 * Impact feed request contracts.
 *
 * The summary is a single aggregate object; the assessments feed is a
 * paginated newest-first array of persisted summaries. Both travel
 * the authenticated path with the Bearer token attached.
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

describe('listImpactAssessments', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    registerAuthBridge(null)
  })

  it('sends an authenticated GET with page and size', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify([
            {
              id: 'assessment-1',
              policyId: 'policy-1',
              versionNumber: 2,
              previousVersionNumber: 1,
              aggregateScore: 42,
              aggregateBand: 'MEDIUM',
              personalizationRulesVersion: 1,
              createdAt: '2026-10-07T10:00:00Z',
            },
          ]),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const assessments = await listImpactAssessments({ page: 0, size: 20 })

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url).toContain('/api/v1/me/impact-assessments')
    expect(url).toContain('page=0')
    expect(url).toContain('size=20')
    expect(init.method ?? 'GET').toBe('GET')
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
    expect(assessments).toHaveLength(1)
    expect(assessments[0]).toMatchObject({
      aggregateBand: 'MEDIUM',
      aggregateScore: 42,
      versionNumber: 2,
      previousVersionNumber: 1,
    })
  })

  it('returns an empty array when no assessments exist', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response('[]', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(listImpactAssessments()).resolves.toEqual([])
  })
})

describe('getImpactSummary', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    registerAuthBridge(null)
  })

  it('requests the single aggregate object', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            totalAssessments: 0,
            assessmentsByBand: [],
            maxAggregateScore: 0,
            actionableRecommendations: 0,
            latest: null,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const summary = await getImpactSummary()

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url.endsWith('/api/v1/me/impact-summary')).toBe(true)
    expect(summary.latest).toBeNull()
  })
})
