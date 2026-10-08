import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NetworkError } from '../../api/errors'
import { registerAuthBridge } from '../../api/client'
import { recommendationDetailPath } from '../../app/routes'
import { formatRelativeTime } from '../../lib/format'
import type { Notification } from '../../api/types'
import {
  canMarkNotificationRead,
  markReadAndReload,
  recommendationMetaLine,
  toMarkReadError,
} from './DashboardPanels'

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

/**
 * Dashboard recommendation rows link to the recommendation detail
 * page. Each row targets `/app/recommendations/{id}` for its own row
 * id, matching the authenticated detail route.
 */
describe('recommendation detail link', () => {
  it('generates the detail path for the row recommendation id', () => {
    expect(recommendationDetailPath('rec-1')).toBe(
      '/app/recommendations/rec-1',
    )
  })
})

/**
 * Notification mark-as-read row action.
 *
 * Unread rows offer the action; read rows never show it. Marking
 * posts once and reloads the feed only on success, so the row flips
 * to `Read`; failures surface the backend's message and skip the
 * reload.
 */
describe('canMarkNotificationRead', () => {
  function notificationWith(
    overrides: Partial<Notification> = {},
  ): Notification {
    return {
      id: 'notif-1',
      assessmentId: 'assessment-1',
      policyId: 'policy-1',
      versionNumber: 2,
      createdAt: '2026-10-07T10:00:00Z',
      readAt: null,
      read: false,
      ...overrides,
    }
  }

  it('offers the action for an unread notification', () => {
    expect(canMarkNotificationRead(notificationWith())).toBe(true)
  })

  it('hides the action for an already-read notification', () => {
    expect(
      canMarkNotificationRead(
        notificationWith({ read: true, readAt: '2026-10-07T11:00:00Z' }),
      ),
    ).toBe(false)
  })
})

describe('toMarkReadError', () => {
  it('surfaces backend failures verbatim', () => {
    expect(
      toMarkReadError(
        new ApiError(404, '/api/v1/me/notifications/missing/read', {
          title: 'Not Found',
          detail: 'Notification not found',
        }),
      ),
    ).toContain('Notification not found')
  })

  it('surfaces network failures as connectivity guidance', () => {
    expect(toMarkReadError(new NetworkError('down'))).toContain(
      'Could not reach the server',
    )
  })
})

describe('markReadAndReload', () => {
  function stubBridge() {
    registerAuthBridge({
      getAccessToken: () => 'test-access-token',
      getRefreshToken: () => null,
      hasExpiredAccessToken: () => false,
      refresh: () => Promise.resolve(null),
      invalidate: () => undefined,
    })
  }

  afterEach(() => {
    vi.unstubAllGlobals()
    registerAuthBridge(null)
  })

  it('posts the mark-read and reloads the feed on success', async () => {
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
    const reload = vi.fn()

    await markReadAndReload('notif-1', reload)

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ]
    expect(url.endsWith('/api/v1/me/notifications/notif-1/read')).toBe(true)
    expect(init.method).toBe('POST')
    expect(reload).toHaveBeenCalledOnce()
  })

  it('skips the reload and propagates the failure on error', async () => {
    stubBridge()
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            title: 'Internal Server Error',
            detail: 'Database unavailable',
          }),
          {
            status: 500,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const reload = vi.fn()

    await expect(markReadAndReload('notif-1', reload)).rejects.toMatchObject({
      status: 500,
    })
    expect(reload).not.toHaveBeenCalled()
  })
})
