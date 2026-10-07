import { apiRequest } from './client'
import type {
  ImpactAssessmentDetail,
  ImpactAssessmentSummary,
  ImpactSummary,
  PageParams,
} from './types'

/**
 * `GET /api/v1/me/impact-summary` — the single aggregate object backing the
 * dashboard's impact panel. Not paginated; the backend returns one summary.
 */
export function getImpactSummary(signal?: AbortSignal): Promise<ImpactSummary> {
  return apiRequest<ImpactSummary>('/api/v1/me/impact-summary', { signal })
}

/**
 * `GET /api/v1/me/impact-assessments` — paginated, newest first.
 * Summaries only: per-assessment score breakdowns stay behind the
 * detail endpoint, which this UI does not surface yet.
 */
export function listImpactAssessments(
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<ImpactAssessmentSummary[]> {
  return apiRequest<ImpactAssessmentSummary[]>('/api/v1/me/impact-assessments', {
    query: { ...params },
    signal,
  })
}

/**
 * `GET /api/v1/me/impact-assessments/{assessmentId}` — one persisted
 * assessment with its ordered score breakdown. Unknown or foreign ids
 * answer `404` and never reveal whether the row exists.
 */
export function getImpactAssessment(
  assessmentId: string,
  signal?: AbortSignal,
): Promise<ImpactAssessmentDetail> {
  return apiRequest<ImpactAssessmentDetail>(
    `/api/v1/me/impact-assessments/${assessmentId}`,
    { signal },
  )
}
