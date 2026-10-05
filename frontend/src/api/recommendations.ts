import { apiRequest } from './client'
import type { PageParams, RecommendationSummary } from './types'

/**
 * `GET /api/v1/me/recommendations` — paginated, newest first. Ownership is
 * derived server-side through the owning assessment, so a foreign id behaves
 * as not-found rather than a 403.
 */
export function listRecommendations(
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<RecommendationSummary[]> {
  return apiRequest<RecommendationSummary[]>('/api/v1/me/recommendations', {
    query: { ...params },
    signal,
  })
}
