import { apiRequest } from './client'
import type {
  UpdatePrivacyPreferencesRequest,
  PrivacyPreference,
} from './types'

/** Privacy-preference endpoints, mirroring `PrivacyPreferenceController`. */

/**
 * The preference surface always covers every vocabulary concept in
 * deterministic order; `explicit` marks values the user actually configured.
 */
export function listPrivacyPreferences(
  signal?: AbortSignal,
): Promise<PrivacyPreference[]> {
  return apiRequest<PrivacyPreference[]>('/api/v1/me/privacy-preferences', {
    signal,
  })
}

/** Bulk-updates sensitivities (0-5) and returns the full resulting surface. */
export function updatePrivacyPreferences(
  request: UpdatePrivacyPreferencesRequest,
): Promise<PrivacyPreference[]> {
  return apiRequest<PrivacyPreference[]>('/api/v1/me/privacy-preferences', {
    method: 'PUT',
    body: request,
  })
}
