import { afterEach, describe, expect, it, vi } from 'vitest'
import { registerAuthBridge } from './client'
import { markNotificationRead } from './notifications'

/**
 * Mark-notification-as-read request contract.
 *
 * `POST /api/v1/me/notifications/{id}/read` marks one row read and
 * returns its updated representation. Unknown or foreign ids answer
 * `404` without revealing whether the row exists.
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

describe('markNotificationRead', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    registerAuthBridge(null)
  })

  it('posts to the read endpoint with authentication', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            id: 'notif-1',
            assessmentId: 'assessment-1',
            policyId: 'policy-1',
            versionNumber: 2,
            createdAt: '2026-10-07T10:00:00Z',
            readAt: '2026-10-07T11:00:00Z',
            read: true,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const updated = await markNotificationRead('notif-1')

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url.endsWith('/api/v1/me/notifications/notif-1/read')).toBe(true)
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Authorization')).toBe(
      'Bearer test-access-token',
    )
    expect(updated).toMatchObject({
      id: 'notif-1',
      read: true,
      readAt: '2026-10-07T11:00:00Z',
    })
  })

  it('surfaces an unknown notification as a 404 failure', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            title: 'Not Found',
            detail: 'Notification not found',
          }),
          {
            status: 404,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(markNotificationRead('missing-id')).rejects.toMatchObject({
      status: 404,
    })
  })
})
