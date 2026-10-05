import type { ProblemDetails } from './types'

/**
 * Error raised when the server returns a non-2xx response.
 *
 * The backend answers every failure with RFC 7807 `application/problem+json`,
 * so the parsed document is preserved verbatim on `problem` rather than being
 * flattened into a string. `fieldErrors` carries the per-field `errors` map
 * that the backend attaches to validation failures.
 */
export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetails | null
  readonly url: string

  constructor(status: number, url: string, problem: ProblemDetails | null) {
    super(ApiError.buildMessage(status, problem))
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.url = url
  }

  /** Per-field validation messages, or an empty map when absent. */
  get fieldErrors(): Record<string, string> {
    return this.problem?.errors ?? {}
  }

  /** True when the failure means "this session is no longer valid". */
  get isUnauthorized(): boolean {
    return this.status === 401
  }

  private static buildMessage(
    status: number,
    problem: ProblemDetails | null,
  ): string {
    const detail = problem?.detail?.trim()
    const title = problem?.title?.trim()
    if (detail && title) return `${title}: ${detail}`
    if (detail) return detail
    if (title) return title
    return `Request failed with status ${status}`
  }
}

/**
 * Error raised when the request never produced an HTTP response — DNS
 * failure, connection refused, CORS rejection, or an aborted request.
 *
 * Kept distinct from {@link ApiError} so the UI can tell "the server said no"
 * apart from "the server was unreachable".
 */
export class NetworkError extends Error {
  override readonly cause: unknown

  constructor(message: string, cause?: unknown) {
    super(message)
    this.name = 'NetworkError'
    this.cause = cause
  }
}

/**
 * Error raised when the session is gone and the UI must return to /login.
 * The auth layer throws this after a refresh failure so that in-flight
 * requests abort instead of hanging or retrying.
 */
export class SessionExpiredError extends Error {
  constructor(message = 'Your session has expired. Please sign in again.') {
    super(message)
    this.name = 'SessionExpiredError'
  }
}

/** Narrowing helper for `catch` blocks that only handle API failures. */
export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

export function isNetworkError(error: unknown): error is NetworkError {
  return error instanceof NetworkError
}

/**
 * Best-effort human-readable message for any thrown value. Never returns
 * `undefined`, so it is always safe to render directly.
 */
export function toErrorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof NetworkError) {
    return 'Could not reach the server. Check your connection and try again.'
  }
  if (error instanceof SessionExpiredError) return error.message
  if (error instanceof Error && error.message) return error.message
  return 'Something went wrong. Please try again.'
}
