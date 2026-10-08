import { apiRequest } from './client'
import type {
  PageParams,
  RecommendationDetail,
  RecommendationSummary,
} from './types'

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

/**
 * `GET /api/v1/me/recommendations/{recommendationId}` — one persisted
 * recommendation plus its assessment navigation (`policyId`,
 * `versionNumber`). Unknown or foreign ids answer `404` and never reveal
 * whether the row exists. `conceptCode` is `null` only for the
 * assessment-level `NONE_REQUIRED` closure row.
 */
export function getRecommendationDetail(
  recommendationId: string,
  signal?: AbortSignal,
): Promise<RecommendationDetail> {
  return apiRequest<RecommendationDetail>(
    `/api/v1/me/recommendations/${recommendationId}`,
    { signal },
  )
}
