import { afterEach, describe, expect, it, vi } from 'vitest'
import { registerAuthBridge } from './client'
import { archivePolicy, reactivatePolicy } from './policies'

/**
 * Archive/reactivate request contract, backing the policy-detail
 * lifecycle buttons.
 *
 * Both calls travel the authenticated path (the backend resolves
 * identity only from the Bearer token) and carry no body: archive is
 * `DELETE` and reactivate is `POST`, each idempotent `204`
 * server-side. A stubbed bridge proves the token is attached.
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

describe('archivePolicy', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    registerAuthBridge(null)
  })

  it('sends an authenticated DELETE with no body', async () => {
    stubBridge()
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await archivePolicy('policy-id-1')

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url.endsWith('/api/v1/policies/policy-id-1')).toBe(true)
    expect(init.method).toBe('DELETE')
    expect(init.body).toBeUndefined()
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
  })
})

describe('reactivatePolicy', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    registerAuthBridge(null)
  })

  it('sends an authenticated POST with no body', async () => {
    stubBridge()
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await reactivatePolicy('policy-id-2')

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url.endsWith('/api/v1/policies/policy-id-2/reactivate')).toBe(true)
    expect(init.method).toBe('POST')
    expect(init.body).toBeUndefined()
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
  })
})
