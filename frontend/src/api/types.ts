/**
 * Shared domain types for the Policy Impact Engine API.
 *
 * Every shape mirrors a backend DTO one-to-one. The backend renders enum
 * values as their `name()` string, so these are string unions constrained to
 * the values the server actually emits — widening them would let the UI render
 * a state the backend can never produce.
 */

/* ---- Enumerations (server-emitted enum names) --------------------- */

export const POLICY_STATUSES = ['ACTIVE', 'ARCHIVED'] as const
export type PolicyStatus = (typeof POLICY_STATUSES)[number]

export const IMPACT_BANDS = [
  'NONE',
  'LOW',
  'MEDIUM',
  'HIGH',
  'CRITICAL',
] as const
export type ImpactBand = (typeof IMPACT_BANDS)[number]

export const RECOMMENDATION_ACTION_KINDS = [
  'EXERCISE_DELETION',
  'OPT_OUT_SHARING',
  'REVIEW_SETTINGS',
  'NONE_REQUIRED',
] as const
export type RecommendationActionKind =
  (typeof RECOMMENDATION_ACTION_KINDS)[number]

export const FETCH_ATTEMPT_STATUSES = [
  'PENDING',
  'IN_PROGRESS',
  'SUCCESS',
  'FAILED',
  'SKIPPED_UNCHANGED',
] as const
export type FetchAttemptStatus = (typeof FETCH_ATTEMPT_STATUSES)[number]

/* ---- Auth ---------------------------------------------------------- */

/** `POST /api/v1/auth/register` request body. */
export interface RegisterRequest {
  email: string
  password: string
}

/** `POST /api/v1/auth/register` -> 201. */
export interface RegistrationResponse {
  id: string
  email: string
}

/** `POST /api/v1/auth/login` request body. */
export interface LoginRequest {
  email: string
  password: string
}

/**
 * `POST /api/v1/auth/login` -> 200 and `POST /api/v1/auth/refresh` -> 200.
 * The backend returns this identical shape for both, and rotates the refresh
 * token on refresh.
 */
export interface TokenResponse {
  accessToken: string
  tokenType: string
  /** Access-token lifetime in seconds. */
  expiresIn: number
  refreshToken: string
  /** Refresh-token lifetime in seconds. */
  refreshExpiresIn: number
}

/** `POST /api/v1/auth/logout` request body. */
export interface LogoutRequest {
  refreshToken: string
}

/* ---- Policies ------------------------------------------------------ */

/** `GET/POST /api/v1/policies`. */
export interface Policy {
  id: string
  name: string
  url: string
  status: PolicyStatus
  createdAt: string
  updatedAt: string
}

/** `POST /api/v1/policies` request body. */
export interface CreatePolicyRequest {
  name: string
  url: string
}

/** `GET /api/v1/policies/{policyId}/overview`. */
export interface PolicyOverview {
  policyId: string
  name: string
  url: string
  status: PolicyStatus
  createdAt: string
  updatedAt: string
  nextCheckAt: string | null
  latestVersion: {
    versionNumber: number
    contentHash: string
    observedAt: string
  } | null
  latestCheck: {
    id: string
    trigger: string
    attemptNumber: number
    status: FetchAttemptStatus
    failureKind: string | null
    httpStatus: number | null
    bytesFetched: number | null
    durationMs: number | null
    startedAt: string
    completedAt: string | null
  } | null
  latestImpact: {
    aggregateScore: number
    band: ImpactBand
    assessedAt: string
  } | null
}

/* ---- Impact / recommendations / notifications ---------------------- */

/** `GET /api/v1/me/impact-summary`. */
export interface ImpactSummary {
  totalAssessments: number
  assessmentsByBand: Array<{ band: ImpactBand; count: number }>
  maxAggregateScore: number
  actionableRecommendations: number
  latest: ImpactAssessmentSummary | null
}

/** Nested inside `ImpactSummary.latest`. */
export interface ImpactAssessmentSummary {
  id: string
  policyId: string
  versionNumber: number
  previousVersionNumber: number
  aggregateScore: number
  aggregateBand: ImpactBand
  personalizationRulesVersion: number
  createdAt: string
}

/** `GET /api/v1/me/recommendations`. */
export interface RecommendationSummary {
  id: string
  assessmentId: string
  ruleId: string
  ruleOrder: number
  actionKind: RecommendationActionKind
  conceptCode: string
  personalizedNormalized: number
  personalizedBand: ImpactBand
  recommendationRulesVersion: number
  createdAt: string
}

/** `GET /api/v1/me/notifications`. */
export interface Notification {
  id: string
  assessmentId: string
  policyId: string
  versionNumber: number
  createdAt: string
  readAt: string | null
  read: boolean
}

/* ---- Privacy / audit ---------------------------------------------- */

/** `GET/PUT /api/v1/me/privacy-preferences`. */
export interface PrivacyPreference {
  conceptCode: string
  label: string
  /** 0-5. */
  effectiveSensitivity: number
  /** False when the value is inherited from the default. */
  explicit: boolean
}

/** `PUT /api/v1/me/privacy-preferences` request body. */
export interface UpdatePrivacyPreferencesRequest {
  preferences: Record<string, number>
}

/** `GET /api/v1/me/audit-events`. */
export interface AuditEvent {
  id: string
  occurredAt: string
  eventType: string
  resourceType: string
  resourceId: string | null
  /** JSON-encoded metadata as a string; never parsed blindly on render. */
  metadata: string | null
  prevHash: string | null
  eventHash: string
}

/* ---- Errors -------------------------------------------------------- */

/**
 * RFC 7807 problem document. The backend's `GlobalExceptionHandler` emits
 * this for every error and adds an `errors` field on validation failures.
 */
export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  errors?: Record<string, string>
}

/** `GET /api/v1/policies/{policyId}/checks` and other paginated feeds. */
export interface PageParams {
  page?: number
  size?: number
}

/**
 * `POST /api/v1/policies/{policyId}/check` -> 200 with the terminal
 * observation result. Mirrors backend `PolicyCheckResponse` exactly:
 * `outcome` is one of `FIRST_VERSION`, `UNCHANGED`, `NEW_VERSION`;
 * `attemptStatus` is `SUCCESS`, or `SKIPPED_UNCHANGED` when the outcome
 * is `UNCHANGED`. Archived/in-flight checks answer `409`, fetch
 * failures `502` — both surface as `ApiError`, never in this shape.
 */
export interface PolicyCheckResult {
  policyId: string
  outcome: string
  versionNumber: number
  contentHash: string
  changeCount: number
  attemptStatus: string
}

/** `GET /api/v1/policies/{policyId}/checks`. */
export interface PolicyCheckHistoryEntry {
  id: string
  trigger: string
  attemptNumber: number
  status: FetchAttemptStatus
  failureKind: string | null
  httpStatus: number | null
  bytesFetched: number | null
  durationMs: number | null
  errorMessage: string | null
  startedAt: string
  completedAt: string | null
}

/**
 * `GET /api/v1/policies/{policyId}/versions` row. Mirrors backend
 * `VersionSummaryResponse` exactly: identity plus the 1-based sequence
 * number, content hash, and observation time — never the normalized
 * content (that lives behind the version-detail endpoint, which this
 * UI does not surface yet).
 */
export interface PolicyVersionSummary {
  id: string
  policyId: string
  versionNumber: number
  contentHash: string
  observedAt: string
}

export const POLICY_CHANGE_TYPES = ['ADDED', 'REMOVED', 'MODIFIED'] as const
export type PolicyChangeType = (typeof POLICY_CHANGE_TYPES)[number]

/**
 * `GET /api/v1/policies/{policyId}/changes` row. Mirrors backend
 * `ChangeRecordResponse` exactly: identity, change type, the old/new
 * texts, the zero-based document position, and the successor version
 * reference. `oldText` is null for `ADDED` rows and `newText` is null
 * for `REMOVED` rows; both are present for `MODIFIED` rows.
 */
export interface PolicyChangeRecord {
  id: string
  changeType: PolicyChangeType
  oldText: string | null
  newText: string | null
  changeOrder: number
  versionNumber: number
  newVersionId: string
}

/**
 * `GET /api/v1/policies/{policyId}/versions/{from}/diff/{to}`.
 * Mirrors backend `VersionDiffResponse` exactly: the requested
 * adjacent transition (`from`/`to` are 1-based version numbers with
 * `to == from + 1`) plus exactly its persisted change rows in
 * `changeOrder` order. The diff is never recomputed and never spans
 * multiple transitions; non-adjacent ranges answer `400`.
 */
export interface PolicyVersionDiff {
  policyId: string
  fromVersion: number
  toVersion: number
  fromVersionId: string
  toVersionId: string
  changes: PolicyChangeRecord[]
}
