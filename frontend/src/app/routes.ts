/**
 * Typed route configuration.
 *
 * Paths are declared once as literal types and reused by the router, the
 * navigation, and every in-app link. This keeps navigation typed (a link to a
 * path that does not exist is a compile error) without a codegen step.
 */

export const ROUTES = {
  home: '/',
  login: '/login',
  register: '/register',
  /** SPA landing for the backend Google callback; carries only the one-time code. */
  googleCallback: '/auth/google/callback',
  app: '/app',
  policies: '/app/policies',
  policyDetail: '/app/policies/:policyId',
  assessments: '/app/assessments',
  assessmentDetail: '/app/assessments/:assessmentId',
  recommendationDetail: '/app/recommendations/:recommendationId',
  privacy: '/app/privacy',
  activity: '/app/activity',
} as const

/** Union of every static path, used to type navigation entries. */
export type RoutePath = (typeof ROUTES)[keyof typeof ROUTES]

/**
 * Builds a policy detail path with a runtime id.
 *
 * Kept as a function rather than a literal in `ROUTES` because the id is
 * dynamic; using `generatePath` semantics keeps it aligned with the router's
 * `:policyId` pattern.
 */
export function policyDetailPath(policyId: string): string {
  return `/app/policies/${encodeURIComponent(policyId)}`
}

/**
 * Builds an assessment detail path with a runtime id.
 */
export function assessmentDetailPath(assessmentId: string): string {
  return `/app/assessments/${encodeURIComponent(assessmentId)}`
}

/**
 * Builds a recommendation detail path with a runtime id.
 */
export function recommendationDetailPath(recommendationId: string): string {
  return `/app/recommendations/${encodeURIComponent(recommendationId)}`
}
