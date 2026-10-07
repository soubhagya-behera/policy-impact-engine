import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '../../api/errors'
import {
  checkNowEnabled,
  checkOutcomeLabel,
  lifecycleAction,
  toLifecycleError,
} from './PolicyDetailPage'

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

  it('surfaces check conflicts verbatim', () => {
    expect(
      toLifecycleError(
        new ApiError(409, '/api/v1/policies/id/check', {
          title: 'Conflict',
          detail: 'Another check is already in progress',
        }),
      ),
    ).toContain('Another check is already in progress')
  })

  it('surfaces fetch failures verbatim', () => {
    expect(
      toLifecycleError(
        new ApiError(502, '/api/v1/policies/id/check', {
          title: 'Bad Gateway',
          detail: 'The policy document could not be fetched',
        }),
      ),
    ).toContain('The policy document could not be fetched')
  })
})

describe('checkNowEnabled', () => {
  it('allows a check for an idle ACTIVE policy', () => {
    expect(checkNowEnabled('ACTIVE', false)).toBe(true)
  })

  it('blocks a check while busy', () => {
    expect(checkNowEnabled('ACTIVE', true)).toBe(false)
  })

  it('blocks a check for non-ACTIVE or unknown status', () => {
    expect(checkNowEnabled('ARCHIVED', false)).toBe(false)
    expect(checkNowEnabled(null, false)).toBe(false)
    expect(checkNowEnabled(undefined, false)).toBe(false)
  })
})

describe('checkOutcomeLabel', () => {
  it('labels every terminal outcome', () => {
    expect(checkOutcomeLabel('FIRST_VERSION')).toBe('First version recorded')
    expect(checkOutcomeLabel('NEW_VERSION')).toBe('New version detected')
    expect(checkOutcomeLabel('UNCHANGED')).toBe('No changes since last check')
  })

  it('renders unknown outcomes verbatim', () => {
    expect(checkOutcomeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})
