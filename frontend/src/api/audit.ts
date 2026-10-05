import { apiRequest } from './client'
import type { AuditEvent, PageParams } from './types'

/**
 * `GET /api/v1/me/audit-events` — read-only, newest-first, hash-chained feed.
 * Listing emits no audit event of its own.
 */
export function listAuditEvents(
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<AuditEvent[]> {
  return apiRequest<AuditEvent[]>('/api/v1/me/audit-events', {
    query: { ...params },
    signal,
  })
}
