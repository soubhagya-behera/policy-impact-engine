import { afterEach, describe, expect, it, vi } from 'vitest'
import { completeGoogleSignIn, googleStartUrl } from './auth'

/**
 * Google handoff API contract.
 *
 * The completion code is exchanged with plain unauthenticated POST body
 * semantics: no `Authorization` header (there is no session yet) and no
 * refresh behaviour on failure (a 401 here means "bad code", not
 * "expired token"). The start URL is a same-origin backend path reached
 * by top-level navigation, never by fetch.
 */
describe('googleStartUrl', () => {
  it('points at the backend start endpoint', () => {
    expect(googleStartUrl().endsWith('/api/v1/auth/google/start')).toBe(true)
  })
})

describe('completeGoogleSignIn', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function mockFetchOnce(status: number, body: unknown) {
    const fetchMock = vi.fn(async () => {
      return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('posts only the code and returns the standard token pair', async () => {
    const fetchMock = mockFetchOnce(200, {
      accessToken: 'access',
      tokenType: 'Bearer',
      expiresIn: 900,
      refreshToken: 'refresh',
      refreshExpiresIn: 2592000,
    })

    const pair = await completeGoogleSignIn('one-time-code')

    expect(pair.accessToken).toBe('access')
    expect(pair.tokenType).toBe('Bearer')
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url.endsWith('/api/v1/auth/google/complete')).toBe(true)
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ code: 'one-time-code' }))
    // No session exists yet, so no Authorization header may be sent and
    // no Google token may appear anywhere in the request.
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBeNull()
    expect(JSON.stringify(init)).not.toContain('Bearer')
  })

  it('surfaces backend failures without refreshing', async () => {
    mockFetchOnce(401, {
      title: 'Unauthenticated',
      detail: 'Invalid Google identity',
    })

    await expect(completeGoogleSignIn('stale-code')).rejects.toMatchObject({
      status: 401,
    })
  })
})
