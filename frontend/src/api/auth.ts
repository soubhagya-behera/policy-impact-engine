import { API_BASE_URL, apiRequest, rawRequest } from './client'
import type {
  LoginRequest,
  LogoutRequest,
  RegisterRequest,
  RegistrationResponse,
  TokenResponse,
} from './types'

/**
 * Authentication endpoints, mirroring `AuthController` exactly.
 *
 * `register`, `login`, `refresh`, and single-session `logout` all use
 * `rawRequest` so a 401 from them is treated as a credential failure rather
 * than triggering a token refresh. Only `logoutAll` is authenticated.
 */

/** `POST /api/v1/auth/register` -> 201 with `{ id, email }`. */
export function register(request: RegisterRequest): Promise<RegistrationResponse> {
  return rawRequest<RegistrationResponse>('/api/v1/auth/register', {
    method: 'POST',
    body: request,
    authenticated: false,
  })
}

/** `POST /api/v1/auth/login` -> 200 with the token pair. */
export function login(request: LoginRequest): Promise<TokenResponse> {
  return rawRequest<TokenResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: request,
    authenticated: false,
  })
}

/**
 * `POST /api/v1/auth/refresh` -> 200 with a rotated token pair.
 * A reused or revoked refresh token yields a uniform 401.
 */
export function refresh(refreshToken: string): Promise<TokenResponse> {
  return rawRequest<TokenResponse>('/api/v1/auth/refresh', {
    method: 'POST',
    body: { refreshToken },
    authenticated: false,
  })
}

/**
 * `POST /api/v1/auth/logout` -> 204. Idempotent on every refresh-token state,
 * so it never rejects on an already-dead token. The caller clears local state
 * regardless of the outcome.
 */
export function logout(request: LogoutRequest): Promise<void> {
  return rawRequest<undefined>('/api/v1/auth/logout', {
    method: 'POST',
    body: request,
    authenticated: false,
  })
}

/** `POST /api/v1/auth/logout-all` -> 204. Revokes every live session. */
export function logoutAll(): Promise<void> {
  return apiRequest<undefined>('/api/v1/auth/logout-all', { method: 'POST' })
}

/**
 * Backend entry point for Google sign-in.
 *
 * Reached by top-level browser navigation (never fetch/XHR), so Spring's
 * OAuth2 state cookie survives the Google redirect. Same-origin in dev
 * (the Vite proxy forwards `/api`), absolute via `VITE_API_BASE_URL` in
 * production.
 */
export function googleStartUrl(): string {
  return `${API_BASE_URL}/api/v1/auth/google/start`
}

/**
 * `POST /api/v1/auth/google/complete` -> 200 with the standard token pair.
 * Exchanges the short-lived single-use completion code the backend OAuth
 * callback minted. Uses `rawRequest` so a 401/409 here is a Google-handoff
 * failure, never a trigger for token refresh.
 */
export function completeGoogleSignIn(code: string): Promise<TokenResponse> {
  return rawRequest<TokenResponse>('/api/v1/auth/google/complete', {
    method: 'POST',
    body: { code },
    authenticated: false,
  })
}
