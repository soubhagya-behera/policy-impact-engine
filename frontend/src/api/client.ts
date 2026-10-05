import { ApiError, NetworkError, SessionExpiredError } from './errors'
import type { ProblemDetails } from './types'

/**
 * Centralized API client.
 *
 * Responsibilities kept here so no component reimplements them:
 *  - resolving the base URL from environment configuration
 *  - attaching `Authorization` where the caller asks for it
 *  - parsing `application/problem+json` into a typed {@link ApiError}
 *  - handling `204 No Content`
 *  - separating network failures from API failures
 *  - refreshing an expired access token exactly once, and never in a loop
 */

const RAW_BASE_URL = import.meta.env['VITE_API_BASE_URL'] ?? ''

/** Same-origin when unset, so the dev proxy handles `/api` (see vite.config). */
export const API_BASE_URL = RAW_BASE_URL.replace(/\/+$/, '')

/** Refresh slightly early so a token cannot expire mid-flight. */
const ACCESS_TOKEN_EPOCH_SKEW_SECONDS = 30

/**
 * Bridge implemented by the auth layer and registered once at startup. Keeping
 * this as an injected interface avoids a circular import between the API layer
 * and the React auth context, while still guaranteeing a single source of
 * truth for token handling.
 */
export interface AuthBridge {
  /** Current access token, or null when signed out. */
  getAccessToken(): string | null
  /** Current refresh token, or null when signed out. */
  getRefreshToken(): string | null
  /** True when the stored access token is absent or about to expire. */
  hasExpiredAccessToken(): boolean
  /** Performs the rotation. Resolves with a new access token, or null on failure. */
  refresh(): Promise<string | null>
  /** Clears session state and notifies listeners that the session is gone. */
  invalidate(reason: 'refresh-failed'): void
}

let authBridge: AuthBridge | null = null

/** Registers the single auth bridge. Called once from the auth provider. */
export function registerAuthBridge(bridge: AuthBridge | null): void {
  authBridge = bridge
}

/**
 * In-flight refresh promise. Concurrent 401s share one rotation instead of
 * firing several, which is what prevents a refresh stampede.
 */
let refreshInFlight: Promise<string | null> | null = null

function runRefresh(): Promise<string | null> {
  refreshInFlight ??= (async () => {
    try {
      return (await authBridge?.refresh()) ?? null
    } catch {
      return null
    } finally {
      refreshInFlight = null
    }
  })()
  return refreshInFlight
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** Serialized as a JSON body with `Content-Type: application/json`. */
  body?: unknown
  /** Attach `Authorization: Bearer <token>`. Defaults to true. */
  authenticated?: boolean
  query?: Record<string, string | number | undefined>
  /**
   * Declared as `AbortSignal | undefined` rather than plain `?: AbortSignal`
   * so callers can pass an optional signal straight through under
   * `exactOptionalPropertyTypes` without a spread guard.
   */
  signal?: AbortSignal | undefined
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const normalized = path.startsWith('/') ? path : `/${path}`
  const url = `${API_BASE_URL}${normalized}`
  if (!query) return url

  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) params.set(key, String(value))
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

/** Reads a response body as JSON, tolerating empty and non-JSON payloads. */
async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined
  const text = await response.text()
  if (!text) return undefined
  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}

function toProblem(body: unknown, status: number): ProblemDetails | null {
  if (body && typeof body === 'object' && !Array.isArray(body)) {
    return body as ProblemDetails
  }
  return typeof body === 'string' && body ? { detail: body, status } : null
}

/**
 * Performs a single request with no refresh behaviour. Exported for the auth
 * endpoints themselves, which must never trigger a refresh (a 401 there means
 * "wrong credentials", not "token expired").
 */
export async function rawRequest<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { method = 'GET', body, query, signal, authenticated = true } = options
  const url = buildUrl(path, query)

  const headers = new Headers({ Accept: 'application/json' })
  if (body !== undefined) headers.set('Content-Type', 'application/json')

  // Only attach a token when one is actually available, so an anonymous call
  // never sends a bogus Authorization header.
  if (authenticated && authBridge) {
    const token = authBridge.getAccessToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
  }

  let response: Response
  try {
    const init: RequestInit = { method, headers }
    if (body !== undefined) init.body = JSON.stringify(body)
    if (signal) init.signal = signal
    response = await fetch(url, init)
  } catch (cause) {
    // An abort is a caller-initiated cancel, not a transport failure.
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new NetworkError('Network request failed', cause)
  }

  const payload = await readBody(response)

  if (!response.ok) {
    throw new ApiError(response.status, url, toProblem(payload, response.status))
  }

  return payload as T
}

/**
 * Performs a request with automatic, single-shot access-token recovery.
 *
 * On a 401 the client refreshes once and replays the original request. If the
 * replay fails with another 401 the session is genuinely gone: the bridge is
 * invalidated and {@link SessionExpiredError} is thrown. Because the replay is
 * a single explicit call rather than a recursion, a failing refresh can never
 * loop.
 */
export async function apiRequest<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const authenticated = options.authenticated ?? true
  if (!authenticated || !authBridge) {
    return rawRequest<T>(path, options)
  }

  // Proactively refresh a token already known to be expired.
  if (authBridge.hasExpiredAccessToken()) {
    const refreshed = await runRefresh()
    if (!refreshed) {
      authBridge.invalidate('refresh-failed')
      throw new SessionExpiredError()
    }
  }

  try {
    return await rawRequest<T>(path, options)
  } catch (error) {
    if (!(error instanceof ApiError) || !error.isUnauthorized) throw error

    const refreshed = await runRefresh()
    if (!refreshed) {
      authBridge.invalidate('refresh-failed')
      throw new SessionExpiredError()
    }

    try {
      // Exactly one replay: the result is returned or the error is thrown.
      return await rawRequest<T>(path, options)
    } catch (retryError) {
      if (retryError instanceof ApiError && retryError.isUnauthorized) {
        authBridge.invalidate('refresh-failed')
        throw new SessionExpiredError()
      }
      throw retryError
    }
  }
}

/** Convenience wrapper for endpoints that answer `204 No Content`. */
export async function apiRequestNoContent(
  path: string,
  options: RequestOptions = {},
): Promise<void> {
  await apiRequest<undefined>(path, options)
}

export { ACCESS_TOKEN_EPOCH_SKEW_SECONDS }
