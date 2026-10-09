import { apiRequest } from './client'
import type { AuditEvent, PageParams } from './types'

/**
 * `GET /api/v1/me/audit-events` — read-only, newest-first, hash-chained feed.
 * Listing emits no audit event of its own.
 *
 * The backend returns a bare JSON array with no total count, windowed by
 * `page`/`size`. The UI loads `AUDIT_FEED_PAGE_SIZE` rows per page and treats
 * a short page as the end of the feed.
 */
export const AUDIT_FEED_PAGE_SIZE = 25

export function listAuditEvents(
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<AuditEvent[]> {
  return apiRequest<AuditEvent[]>('/api/v1/me/audit-events', {
    query: { ...params },
    signal,
  })
}
