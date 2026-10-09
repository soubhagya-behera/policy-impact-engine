// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuditEvent } from '../../api/types'
import { ActivityPage } from './ActivityPage'
import { listAuditEvents } from '../../api/audit'

vi.mock('../../api/audit', () => ({
  AUDIT_FEED_PAGE_SIZE: 25,
  listAuditEvents: vi.fn(),
}))

const mockedListAuditEvents = vi.mocked(listAuditEvents)

function makeEvent(index: number): AuditEvent {
  return {
    id: `event-${index}`,
    occurredAt: '2026-10-07T10:00:00Z',
    eventType: `EVENT_${index}`,
    resourceType: 'USER',
    resourceId: `user-${index}`,
    metadata: null,
    prevHash: null,
    eventHash: `hash-${index}`,
  }
}

function makePage(start: number, count: number): AuditEvent[] {
  return Array.from({ length: count }, (_, offset) => makeEvent(start + offset))
}

function listItemOrder(): string[] {
  return screen
    .getAllByRole('listitem')
    .map((item) => item.textContent ?? '')
}

beforeEach(() => {
  mockedListAuditEvents.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('ActivityPage pagination', () => {
  it('loads the initial page with page 0 and size 25', async () => {
    mockedListAuditEvents.mockResolvedValueOnce(makePage(0, 25))

    render(<ActivityPage />)

    await screen.findByText('EVENT_0')
    expect(mockedListAuditEvents).toHaveBeenCalledTimes(1)
    expect(mockedListAuditEvents).toHaveBeenCalledWith(
      { page: 0, size: 25 },
      expect.anything(),
    )
    expect(
      screen.getByRole('button', { name: 'Load more' }),
    ).toBeDefined()
  })

  it('appends the next page in order without duplicating events', async () => {
    mockedListAuditEvents.mockResolvedValueOnce(makePage(0, 25))
    mockedListAuditEvents.mockResolvedValueOnce(makePage(25, 25))

    render(<ActivityPage />)
    await screen.findByText('EVENT_24')

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await screen.findByText('EVENT_49')

    expect(mockedListAuditEvents).toHaveBeenCalledTimes(2)
    expect(mockedListAuditEvents).toHaveBeenNthCalledWith(
      2,
      { page: 1, size: 25 },
    )
    const order = listItemOrder()
    expect(order).toHaveLength(50)
    expect(order[0]).toContain('EVENT_0')
    expect(order[25]).toContain('EVENT_25')
    expect(order[49]).toContain('EVENT_49')
    // Full second page keeps the button available.
    expect(
      screen.getByRole('button', { name: 'Load more' }),
    ).toBeDefined()
  })

  it('hides Load more when the first page is short', async () => {
    mockedListAuditEvents.mockResolvedValueOnce(makePage(0, 3))

    render(<ActivityPage />)
    await screen.findByText('EVENT_2')

    expect(
      screen.queryByRole('button', { name: /load more/i }),
    ).toBeNull()
  })

  it('hides Load more when the appended page is short', async () => {
    mockedListAuditEvents.mockResolvedValueOnce(makePage(0, 25))
    mockedListAuditEvents.mockResolvedValueOnce(makePage(25, 7))

    render(<ActivityPage />)
    await screen.findByText('EVENT_24')

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await screen.findByText('EVENT_31')

    expect(listItemOrder()).toHaveLength(32)
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: /load more/i }),
      ).toBeNull()
    })
  })

  it('disables the button while loading and ignores duplicate clicks', async () => {
    mockedListAuditEvents.mockResolvedValueOnce(makePage(0, 25))
    let resolvePage1!: (events: AuditEvent[]) => void
    mockedListAuditEvents.mockImplementationOnce(
      () =>
        new Promise<AuditEvent[]>((resolve) => {
          resolvePage1 = resolve
        }),
    )

    render(<ActivityPage />)
    await screen.findByText('EVENT_24')

    const loadMore = screen.getByRole('button', { name: 'Load more' })
    fireEvent.click(loadMore)
    fireEvent.click(loadMore)
    fireEvent.click(loadMore)

    // One initial call plus exactly one next-page request.
    expect(mockedListAuditEvents).toHaveBeenCalledTimes(2)
    expect(
      screen.getByRole('button', { name: /loading more/i }),
    ).toHaveProperty('disabled', true)

    resolvePage1(makePage(25, 25))
    await screen.findByText('EVENT_49')
    expect(listItemOrder()).toHaveLength(50)
    expect(mockedListAuditEvents).toHaveBeenCalledTimes(2)
  })

  it('preserves loaded events on next-page failure and retries the same page', async () => {
    mockedListAuditEvents.mockResolvedValueOnce(makePage(0, 25))
    mockedListAuditEvents.mockRejectedValueOnce(new Error('boom'))

    render(<ActivityPage />)
    await screen.findByText('EVENT_24')

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await screen.findByRole('alert')

    // Already-loaded events survive; the error is shown with a retry.
    expect(screen.getByText('EVENT_0')).toBeDefined()
    expect(screen.getByText('EVENT_24')).toBeDefined()
    expect(screen.getByText('boom')).toBeDefined()
    expect(listItemOrder()).toHaveLength(25)

    mockedListAuditEvents.mockResolvedValueOnce(makePage(25, 25))
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByText('EVENT_49')

    // Retried page 1 — no duplicates, error cleared.
    expect(mockedListAuditEvents).toHaveBeenCalledTimes(3)
    expect(mockedListAuditEvents).toHaveBeenNthCalledWith(
      3,
      { page: 1, size: 25 },
    )
    const order = listItemOrder()
    expect(order).toHaveLength(50)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('keeps the initial error and retry behavior when the first page fails', async () => {
    mockedListAuditEvents.mockRejectedValueOnce(new Error('first page down'))

    render(<ActivityPage />)
    await screen.findByText('first page down')

    expect(screen.queryByRole('list')).toBeNull()

    mockedListAuditEvents.mockResolvedValueOnce(makePage(0, 2))
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByText('EVENT_1')
    expect(listItemOrder()).toHaveLength(2)
  })
})
