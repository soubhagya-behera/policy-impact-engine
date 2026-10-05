import { apiRequest } from './client'
import type { ImpactSummary } from './types'

/**
 * `GET /api/v1/me/impact-summary` — the single aggregate object backing the
 * dashboard's impact panel. Not paginated; the backend returns one summary.
 */
export function getImpactSummary(signal?: AbortSignal): Promise<ImpactSummary> {
  return apiRequest<ImpactSummary>('/api/v1/me/impact-summary', { signal })
}
