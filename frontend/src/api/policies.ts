import { apiRequest, apiRequestNoContent } from './client'
import type {
  CreatePolicyRequest,
  PageParams,
  Policy,
  PolicyChangeRecord,
  PolicyCheckHistoryEntry,
  PolicyCheckResult,
  PolicyOverview,
  PolicyVersionSummary,
} from './types'

/** Policy endpoints, mirroring `PolicyController`. */

export function listPolicies(
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<Policy[]> {
  return apiRequest<Policy[]>('/api/v1/policies', {
    query: { ...params },
    signal,
  })
}

export function getPolicy(policyId: string): Promise<Policy> {
  return apiRequest<Policy>(`/api/v1/policies/${policyId}`)
}

/** `POST /api/v1/policies` -> 201 with the registered policy. */
export function createPolicy(request: CreatePolicyRequest): Promise<Policy> {
  return apiRequest<Policy>('/api/v1/policies', {
    method: 'POST',
    body: request,
  })
}

/** Archives the policy. Repeat calls stay 204. */
export function archivePolicy(policyId: string): Promise<void> {
  return apiRequestNoContent(`/api/v1/policies/${policyId}`, {
    method: 'DELETE',
  })
}

export function reactivatePolicy(policyId: string): Promise<void> {
  return apiRequestNoContent(`/api/v1/policies/${policyId}/reactivate`, {
    method: 'POST',
  })
}

/** Read-only current-state overview: latest version, check, and impact. */
export function getPolicyOverview(
  policyId: string,
  signal?: AbortSignal,
): Promise<PolicyOverview> {
  return apiRequest<PolicyOverview>(`/api/v1/policies/${policyId}/overview`, {
    signal,
  })
}

export function listPolicyChecks(
  policyId: string,
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<PolicyCheckHistoryEntry[]> {
  return apiRequest<PolicyCheckHistoryEntry[]>(
    `/api/v1/policies/${policyId}/checks`,
    { query: { ...params }, signal },
  )
}

/**
 * Version history, windowed by `page`/`size` like every other feed.
 * The backend orders ascending by version number; newest-first
 * presentation is the caller's concern (see `sortVersionsNewestFirst`
 * on the detail page).
 */
export function listPolicyVersions(
  policyId: string,
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<PolicyVersionSummary[]> {
  return apiRequest<PolicyVersionSummary[]>(
    `/api/v1/policies/${policyId}/versions`,
    { query: { ...params }, signal },
  )
}

/**
 * Change history, windowed by `page`/`size` like every other feed.
 * Transport order is the backend's transition order (successor
 * version ascending, then document position ascending) and is
 * rendered verbatim — never resorted client-side.
 */
export function listPolicyChanges(
  policyId: string,
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<PolicyChangeRecord[]> {
  return apiRequest<PolicyChangeRecord[]>(
    `/api/v1/policies/${policyId}/changes`,
    { query: { ...params }, signal },
  )
}

/**
 * Triggers a manual check. The backend answers 200 with the terminal
 * `PolicyCheckResult`, 409 when another check already holds the claim
 * or the policy is archived, and 502 on a fetch failure — the latter
 * surface to the caller as an `ApiError` and are handled per-panel.
 */
export function runPolicyCheck(policyId: string): Promise<PolicyCheckResult> {
  return apiRequest<PolicyCheckResult>(`/api/v1/policies/${policyId}/check`, {
    method: 'POST',
  })
}
