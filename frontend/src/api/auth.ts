import { apiRequest, rawRequest } from './client'
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
