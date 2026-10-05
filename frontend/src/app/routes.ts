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
  app: '/app',
  policies: '/app/policies',
  policyDetail: '/app/policies/:policyId',
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
