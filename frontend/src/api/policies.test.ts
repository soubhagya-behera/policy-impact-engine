import { afterEach, describe, expect, it, vi } from 'vitest'
import { registerAuthBridge } from './client'
import {
  archivePolicy,
  listPolicyChecks,
  listPolicyChanges,
  listPolicyVersions,
  reactivatePolicy,
  runPolicyCheck,
} from './policies'

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

describe('runPolicyCheck', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    registerAuthBridge(null)
  })

  it('sends an authenticated POST and returns the terminal result', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            policyId: 'policy-id-3',
            outcome: 'NEW_VERSION',
            versionNumber: 2,
            contentHash: 'abc123',
            changeCount: 4,
            attemptStatus: 'SUCCESS',
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await runPolicyCheck('policy-id-3')

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url.endsWith('/api/v1/policies/policy-id-3/check')).toBe(true)
    expect(init.method).toBe('POST')
    expect(init.body).toBeUndefined()
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
    expect(result).toMatchObject({
      outcome: 'NEW_VERSION',
      versionNumber: 2,
      changeCount: 4,
      attemptStatus: 'SUCCESS',
    })
  })
})

describe('listPolicyChecks', () => {
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
              id: 'attempt-1',
              trigger: 'MANUAL',
              attemptNumber: 2,
              status: 'SUCCESS',
              failureKind: null,
              httpStatus: 200,
              bytesFetched: 1234,
              durationMs: 567,
              errorMessage: null,
              startedAt: '2026-10-07T10:00:00Z',
              completedAt: '2026-10-07T10:00:01Z',
            },
          ]),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const entries = await listPolicyChecks(
      'policy-id-4',
      { page: 0, size: 10 },
    )

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url).toContain('/api/v1/policies/policy-id-4/checks')
    expect(url).toContain('page=0')
    expect(url).toContain('size=10')
    expect(init.method ?? 'GET').toBe('GET')
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
    expect(entries).toHaveLength(1)
    expect(entries[0]).toMatchObject({
      trigger: 'MANUAL',
      attemptNumber: 2,
      status: 'SUCCESS',
    })
  })
})

describe('listPolicyVersions', () => {
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
              id: 'version-1',
              policyId: 'policy-id-5',
              versionNumber: 1,
              contentHash: 'deadbeef',
              observedAt: '2026-10-07T10:00:00Z',
            },
          ]),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const versions = await listPolicyVersions(
      'policy-id-5',
      { page: 0, size: 10 },
    )

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url).toContain('/api/v1/policies/policy-id-5/versions')
    expect(url).toContain('page=0')
    expect(url).toContain('size=10')
    expect(init.method ?? 'GET').toBe('GET')
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
    expect(versions).toHaveLength(1)
    expect(versions[0]).toMatchObject({
      versionNumber: 1,
      contentHash: 'deadbeef',
    })
  })
})

describe('listPolicyChanges', () => {
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
              id: 'change-1',
              changeType: 'ADDED',
              oldText: null,
              newText: 'new clause text',
              changeOrder: 0,
              versionNumber: 2,
              newVersionId: 'version-2',
            },
          ]),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const changes = await listPolicyChanges(
      'policy-id-6',
      { page: 0, size: 10 },
    )

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url).toContain('/api/v1/policies/policy-id-6/changes')
    expect(url).toContain('page=0')
    expect(url).toContain('size=10')
    expect(init.method ?? 'GET').toBe('GET')
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
    expect(changes).toHaveLength(1)
    expect(changes[0]).toMatchObject({
      changeType: 'ADDED',
      oldText: null,
      newText: 'new clause text',
      changeOrder: 0,
      versionNumber: 2,
    })
  })
})
