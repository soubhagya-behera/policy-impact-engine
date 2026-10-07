import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '../../api/errors'
import { toAssessmentError } from './AssessmentDetailPage'

/**
 * Assessment-detail failure mapping.
 *
 * Unknown or foreign ids answer `404` without revealing whether the
 * row exists, so the page names nothing beyond "no longer exists";
 * every other failure surfaces the backend's own message.
 */
describe('toAssessmentError', () => {
  it('maps a missing assessment to a plain explanation', () => {
    expect(
      toAssessmentError(
        new ApiError(404, '/api/v1/me/impact-assessments/missing', {
          title: 'Not Found',
          detail: 'Assessment not found',
        }),
      ),
    ).toContain('no longer exists')
  })

  it('surfaces other backend failures verbatim', () => {
    expect(
      toAssessmentError(
        new ApiError(429, '/api/v1/me/impact-assessments/id', {
          title: 'Too Many Requests',
          detail: 'Rate limit exceeded, retry after 5 seconds',
        }),
      ),
    ).toContain('Rate limit exceeded')
  })

  it('surfaces network failures as connectivity guidance', () => {
    expect(toAssessmentError(new NetworkError('down'))).toContain(
      'Could not reach the server',
    )
  })
})
