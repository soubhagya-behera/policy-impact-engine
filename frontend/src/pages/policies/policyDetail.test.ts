import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '../../api/errors'
import { lifecycleAction, toLifecycleError } from './PolicyDetailPage'

/**
 * Policy-detail lifecycle mapping.
 *
 * `ACTIVE` offers archive, `ARCHIVED` offers reactivate, and anything
 * else (including not-yet-loaded) offers no action. A `404` means the
 * policy is gone; every other failure surfaces the backend's own
 * message, including conflicts and network errors.
 */
describe('lifecycleAction', () => {
  it('maps ACTIVE to archive', () => {
    expect(lifecycleAction('ACTIVE')).toBe('archive')
  })

  it('maps ARCHIVED to reactivate', () => {
    expect(lifecycleAction('ARCHIVED')).toBe('reactivate')
  })

  it('offers no action without a known status', () => {
    expect(lifecycleAction(null)).toBeNull()
    expect(lifecycleAction(undefined)).toBeNull()
  })
})

describe('toLifecycleError', () => {
  it('maps a missing policy to a plain explanation', () => {
    expect(
      toLifecycleError(
        new ApiError(404, '/api/v1/policies/id', {
          title: 'Not Found',
          detail: 'Resource not found',
        }),
      ),
    ).toContain('no longer exists')
  })

  it('surfaces other backend failures verbatim', () => {
    expect(
      toLifecycleError(
        new ApiError(409, '/api/v1/policies/id', {
          title: 'Conflict',
          detail: 'Policy is archived',
        }),
      ),
    ).toContain('Policy is archived')
  })

  it('surfaces network failures as connectivity guidance', () => {
    expect(toLifecycleError(new NetworkError('down'))).toContain(
      'Could not reach the server',
    )
  })
})
