import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '../../api/errors'
import { toGoogleCallbackError } from './GoogleCallbackPage'

/**
 * Google callback error mapping.
 *
 * Backend failures become clear but generic user-facing messages: the
 * 409 case names the next step (password sign-in) without revealing
 * whether any account exists beyond the collision the user caused, and
 * expired/reused codes share one message so code state never leaks.
 */
describe('toGoogleCallbackError', () => {
  it('maps expired or reused codes to one retry message', () => {
    const message = toGoogleCallbackError(
      new ApiError(401, '/api/v1/auth/google/complete', {
        title: 'Unauthenticated',
        detail: 'Invalid Google identity',
      }),
    )
    expect(message).toContain('expired or was already used')
  })

  it('maps an existing-email conflict to password sign-in guidance', () => {
    const message = toGoogleCallbackError(
      new ApiError(409, '/api/v1/auth/google/complete', {
        title: 'Already registered',
        detail: 'An account with that email already exists',
      }),
    )
    expect(message).toContain('already exists')
    expect(message).toContain('password')
  })

  it('maps rate limiting to a wait-and-retry message', () => {
    const message = toGoogleCallbackError(
      new ApiError(429, '/api/v1/auth/google/complete', {
        title: 'Too Many Requests',
        detail: 'Rate limit exceeded, retry after 12 seconds',
      }),
    )
    expect(message).toContain('Too many attempts')
  })

  it('maps network failures to a connectivity message', () => {
    expect(toGoogleCallbackError(new NetworkError('down'))).toContain(
      'Could not reach the server',
    )
  })
})
