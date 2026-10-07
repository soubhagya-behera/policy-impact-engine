import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { archivePolicy, getPolicyOverview, getVersionDiff, listPolicyChanges, listPolicyChecks, listPolicyVersions, reactivatePolicy, runPolicyCheck } from '../../api/policies'
import { isApiError, toErrorMessage } from '../../api/errors'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Button } from '../../components/ui/Button'
import { PageContainer } from '../../components/layout/PageContainer'
import { PageHeader, Section } from '../../components/ui/Layout'
import { StatusChip } from '../../components/ui/StatusChip'
import { useAsyncData } from '../../hooks/useAsyncData'
import {
  bandColor,
  bandLabel,
  changeTypeLabel,
  formatBytes,
  formatDateTime,
  formatDurationMs,
  statusLabel,
} from '../../lib/format'
import { ROUTES } from '../../app/routes'
import type { PolicyChangeRecord, PolicyCheckHistoryEntry, PolicyCheckResult, PolicyOverview, PolicyStatus, PolicyVersionDiff, PolicyVersionSummary } from '../../api/types'

/**
 * Lifecycle action available for a policy status.
 *
 * `ACTIVE` policies can be archived; `ARCHIVED` policies can be
 * reactivated; anything else (including not-yet-loaded) offers no
 * action. Both operations are idempotent server-side (`204` on
 * repeats), so this mapping only decides which button to show.
 */
export function lifecycleAction(
  status: PolicyStatus | null | undefined,
): 'archive' | 'reactivate' | null {
  if (status === 'ACTIVE') return 'archive'
  if (status === 'ARCHIVED') return 'reactivate'
  return null
}

/**
 * User-facing message for a failed archive/reactivate request.
 * A `404` means the policy is gone (or never belonged to this
 * account); every other failure surfaces the backend's own message
 * verbatim, including validation, conflict, and network errors.
 */
export function toLifecycleError(error: unknown): string {
  if (isApiError(error) && error.status === 404) {
    return 'This policy no longer exists. It may have been removed.'
  }
  return toErrorMessage(error)
}

/**
 * Whether the manual "Check now" action may fire: only for an
 * `ACTIVE` policy while no check (and no overview refresh it would
 * race) is running. Pinned by tests because the button's disabled
 * state cannot be asserted without a DOM harness.
 */
export function checkNowEnabled(
  status: PolicyStatus | null | undefined,
  busy: boolean,
): boolean {
  return status === 'ACTIVE' && !busy
}

/**
 * Human label for a terminal manual-check outcome. Unknown future
 * outcomes render verbatim rather than crashing or inventing text.
 */
export function checkOutcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'FIRST_VERSION':
      return 'First version recorded'
    case 'NEW_VERSION':
      return 'New version detected'
    case 'UNCHANGED':
      return 'No changes since last check'
    default:
      return outcome
  }
}

/**
 * Presents version history newest-first. The backend feed orders
 * ascending by version number, so the page sorts explicitly rather
 * than relying on transport order.
 */
export function sortVersionsNewestFirst(
  versions: PolicyVersionSummary[],
): PolicyVersionSummary[] {
  return [...versions].sort((a, b) => b.versionNumber - a.versionNumber)
}

/**
 * Default diff selection: the two newest distinct version numbers.
 * Returns null until at least two versions exist.
 */
export function latestAdjacentPair(
  versions: PolicyVersionSummary[],
): { from: number; to: number } | null {
  const numbers = [...new Set(versions.map((version) => version.versionNumber))].sort(
    (a, b) => b - a,
  )
  const [to, from] = numbers
  if (to === undefined || from === undefined) return null
  return { from, to }
}

/**
 * Validates a user-selected diff pair against the backend's
 * adjacent-only rule (`to` must equal `from + 1`). Returns null when
 * the pair may be requested, otherwise the message to show instead
 * of firing a request that is guaranteed to answer `400`.
 */
export function validateDiffPair(
  from: number | null,
  to: number | null,
): string | null {
  if (from === null || to === null) {
    return 'Select two versions to compare.'
  }
  if (to !== from + 1) {
    return 'Only adjacent versions can be compared, for example v2 and v3.'
  }
  return null
}

/**
 * Policy detail foundation.
 *
 * Phase 18-A establishes the shell and surfaces the real overview from
 * `GET /api/v1/policies/{policyId}/overview` (latest version, latest check,
 * latest impact). Version history, diffs, and change records are deliberately
 * left for a later phase rather than faked here.
 */
export function PolicyDetailPage() {
  const { policyId } = useParams<{ policyId: string }>()
  const [isActing, setIsActing] = useState(false)
  const [isChecking, setIsChecking] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [checkResult, setCheckResult] = useState<PolicyCheckResult | null>(null)
  const [fromVersion, setFromVersion] = useState<number | null>(null)
  const [toVersion, setToVersion] = useState<number | null>(null)
  const [diffResult, setDiffResult] = useState<PolicyVersionDiff | null>(null)
  const [isDiffLoading, setIsDiffLoading] = useState(false)
  const [diffError, setDiffError] = useState<string | null>(null)

  const overview = useAsyncData<PolicyOverview>(
    (signal) => getPolicyOverview(policyId ?? '', signal),
    () => false,
    { enabled: Boolean(policyId) },
  )

  const checks = useAsyncData<PolicyCheckHistoryEntry[]>(
    (signal) => listPolicyChecks(policyId ?? '', { page: 0, size: 10 }, signal),
    (data) => data.length === 0,
    { enabled: Boolean(policyId) },
  )

  const versions = useAsyncData<PolicyVersionSummary[]>(
    (signal) => listPolicyVersions(policyId ?? '', { page: 0, size: 10 }, signal),
    (data) => data.length === 0,
    { enabled: Boolean(policyId) },
  )

  // Diff selectors default to the two newest snapshots once history
  // loads. Derived during render (not an effect), so an explicit user
  // selection in state always wins and there is no first-frame flash.
  const versionOptions = versions.data
    ? sortVersionsNewestFirst(versions.data)
    : []
  const defaultPair = latestAdjacentPair(versionOptions)
  const effectiveFrom = fromVersion ?? defaultPair?.from ?? null
  const effectiveTo = toVersion ?? defaultPair?.to ?? null
  const hasVersionsForDiff = versionOptions.length >= 2

  const changes = useAsyncData<PolicyChangeRecord[]>(
    (signal) => listPolicyChanges(policyId ?? '', { page: 0, size: 10 }, signal),
    (data) => data.length === 0,
    { enabled: Boolean(policyId) },
  )

  if (!policyId) {
    return (
      <PageContainer>
        <EmptyState
          title="Policy not found"
          description="That policy reference is missing from the URL."
          action={
            <Link to={ROUTES.policies} className="inline-flex">
              <Button variant="secondary">Back to policies</Button>
            </Link>
          }
        />
      </PageContainer>
    )
  }

  const data = overview.data
  const action = lifecycleAction(data?.status)
  // Disabled until the overview has loaded and while a lifecycle
  // request (or its follow-up refresh) is in flight, so the button
  // can never fire against stale or unknown state.
  const actionDisabled = isActing || overview.status === 'loading'

  const checkBusy = isChecking || overview.status === 'loading'

  const fetchVersionDiff = async (from: number | null, to: number | null) => {
    if (!policyId) return
    const invalid = validateDiffPair(from, to)
    if (invalid || from === null || to === null) {
      setDiffError(invalid ?? 'Select two versions to compare.')
      return
    }
    setDiffError(null)
    setIsDiffLoading(true)
    try {
      // Adjacency was validated above: the backend compares 1-based
      // version numbers, never row ids.
      const result = await getVersionDiff(policyId, from, to)
      setDiffResult(result)
    } catch (error) {
      setDiffResult(null)
      setDiffError(toLifecycleError(error))
    } finally {
      setIsDiffLoading(false)
    }
  }

  const runManualCheck = async () => {    if (!policyId || !checkNowEnabled(data?.status, checkBusy)) return
    setActionError(null)
    setIsChecking(true)
    try {
      const result = await runPolicyCheck(policyId)
      setCheckResult(result)
      // Re-read the overview, the check history, and the version
      // history so every block reflects the check that just ran (a
      // check can record a brand-new version).
      overview.reload()
      checks.reload()
      versions.reload()
    } catch (error) {
      // 409 (archived/in-flight), 502 (fetch failure), 404, and
      // network errors all land here with the backend's message.
      setActionError(toLifecycleError(error))
    } finally {
      setIsChecking(false)
    }
  }

  const runLifecycleAction = async () => {
    if (!policyId || !action || actionDisabled) return
    setActionError(null)
    setIsActing(true)
    try {
      if (action === 'archive') {
        await archivePolicy(policyId)
      } else {
        await reactivatePolicy(policyId)
      }
      // Re-read the overview: the fresh status renders from real
      // backend state with no browser reload.
      overview.reload()
    } catch (error) {
      setActionError(toLifecycleError(error))
    } finally {
      setIsActing(false)
    }
  }

  return (
    <PageContainer>
      <PageHeader
        label="Policy"
        title={data?.name ?? 'Policy detail'}
        {...(data?.url ? { description: data.url } : {})}
        actions={
          <>
            {data?.status === 'ACTIVE' ? (
              <Button
                variant="primary"
                onClick={() => void runManualCheck()}
                isLoading={isChecking}
                disabled={checkBusy}
              >
                {isChecking ? 'Checking…' : 'Check now'}
              </Button>
            ) : null}
            {action === 'archive' ? (
              <Button
                variant="danger"
                onClick={() => void runLifecycleAction()}
                isLoading={isActing}
                disabled={actionDisabled}
              >
                {isActing ? 'Archiving…' : 'Archive'}
              </Button>
            ) : null}
            {action === 'reactivate' ? (
              <Button
                variant="primary"
                onClick={() => void runLifecycleAction()}
                isLoading={isActing}
                disabled={actionDisabled}
              >
                {isActing ? 'Reactivating…' : 'Reactivate'}
              </Button>
            ) : null}
            <Link to={ROUTES.policies}>
              <Button variant="secondary">Back to policies</Button>
            </Link>
          </>
        }
      />

      {actionError ? (
        <div
          role="alert"
          className="border border-accent-soft/40 bg-surface px-4 py-3 font-body text-base text-accent-soft"
        >
          {actionError}
        </div>
      ) : null}

      {overview.isInitialLoading ? <SkeletonRows rows={3} /> : null}

      {overview.status === 'error' && overview.errorMessage ? (
        <ErrorState message={overview.errorMessage} onRetry={overview.reload} />
      ) : null}

      {data ? (
        <>
          <Section title="Current state">
            <dl className="grid grid-cols-1 gap-x-8 gap-y-8 sm:grid-cols-3">
              <div>
                <dt className="type-label text-ink-faint">Status</dt>
                <dd className="mt-3">
                  <StatusChip
                    label={statusLabel(data.status)}
                    tone={data.status === 'ACTIVE' ? 'positive' : 'neutral'}
                  />
                </dd>
              </div>
              <div>
                <dt className="type-label text-ink-faint">Latest version</dt>
                <dd className="mt-3 font-body text-base text-ink">
                  {data.latestVersion
                    ? `v${data.latestVersion.versionNumber}`
                    : 'Not observed yet'}
                </dd>
                {data.latestVersion ? (
                  <dd className="mt-1 font-body text-sm text-ink-ghost">
                    <time dateTime={data.latestVersion.observedAt}>
                      {formatDateTime(data.latestVersion.observedAt)}
                    </time>
                  </dd>
                ) : null}
              </div>
              <div>
                <dt className="type-label text-ink-faint">Privacy impact</dt>
                <dd
                  className={`mt-3 font-body text-base ${
                    data.latestImpact ? bandColor(data.latestImpact.band) : 'text-ink'
                  }`}
                >
                  {data.latestImpact
                    ? `${bandLabel(data.latestImpact.band)} (${data.latestImpact.aggregateScore})`
                    : 'Not assessed yet'}
                </dd>
              </div>
            </dl>
          </Section>

          <Section
            title="Latest check"
            description="The most recent fetch attempt for this document."
          >
            {data.latestCheck ? (
              <div className="border-y border-line py-6">
                <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
                  <span className="font-body text-base text-ink">
                    {data.latestCheck.status}
                  </span>
                  <span className="font-body text-sm text-ink-ghost">
                    Attempt {data.latestCheck.attemptNumber} ·{' '}
                    {data.latestCheck.trigger}
                  </span>
                  {data.latestCheck.httpStatus ? (
                    <span className="font-body text-sm text-ink-ghost">
                      HTTP {data.latestCheck.httpStatus}
                    </span>
                  ) : null}
                </div>
                <p className="mt-3 font-body text-sm text-ink-ghost">
                  <time dateTime={data.latestCheck.startedAt}>
                    {formatDateTime(data.latestCheck.startedAt)}
                  </time>
                </p>
              </div>
            ) : (
              <EmptyState
                title="No checks recorded"
                description="This policy has not been checked yet. The next check is scheduled automatically."
              />
            )}
          </Section>

          {checkResult ? (
            <Section
              title="Check result"
              description="The terminal outcome of the manual check just run."
            >
              <dl className="grid grid-cols-1 gap-x-8 gap-y-8 sm:grid-cols-3">
                <div>
                  <dt className="type-label text-ink-faint">Outcome</dt>
                  <dd className="mt-3 font-body text-base text-ink">
                    {checkOutcomeLabel(checkResult.outcome)}
                  </dd>
                </div>
                <div>
                  <dt className="type-label text-ink-faint">Version</dt>
                  <dd className="mt-3 font-body text-base text-ink">
                    v{checkResult.versionNumber}
                  </dd>
                  <dd className="mt-1 font-body text-sm text-ink-ghost">
                    {checkResult.changeCount}{' '}
                    {checkResult.changeCount === 1 ? 'change' : 'changes'}
                  </dd>
                </div>
                <div>
                  <dt className="type-label text-ink-faint">Attempt</dt>
                  <dd className="mt-3 font-body text-base text-ink">
                    {checkResult.attemptStatus}
                  </dd>
                </div>
              </dl>
            </Section>
          ) : null}

          <Section
            title="Check history"
            description="Every recorded check for this document, newest first."
            actions={
              <Button
                variant="secondary"
                onClick={checks.reload}
                isLoading={checks.status === 'loading'}
              >
                Refresh
              </Button>
            }
          >
            {checks.isInitialLoading ? <SkeletonRows rows={3} /> : null}

            {checks.status === 'error' && checks.errorMessage ? (
              <ErrorState message={checks.errorMessage} onRetry={checks.reload} />
            ) : null}

            {checks.isEmpty ? (
              <EmptyState
                title="No checks recorded yet"
                description="Run a manual check or wait for the next scheduled check to see its history here."
              />
            ) : null}

            {checks.data && checks.data.length > 0 ? (
              <ul className="divide-y divide-line border-y border-line">
                {checks.data.map((entry) => (
                  <li key={entry.id} className="py-5">
                    <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
                      <span className="font-body text-base text-ink">
                        {entry.status}
                      </span>
                      <span className="font-body text-sm text-ink-ghost">
                        Attempt {entry.attemptNumber} · {entry.trigger}
                      </span>
                      {entry.httpStatus ? (
                        <span className="font-body text-sm text-ink-ghost">
                          HTTP {entry.httpStatus}
                        </span>
                      ) : null}
                      {entry.failureKind ? (
                        <span className="font-body text-sm text-ink-ghost">
                          {entry.failureKind}
                        </span>
                      ) : null}
                    </div>
                    <p className="mt-3 font-body text-sm text-ink-ghost">
                      <time dateTime={entry.startedAt}>
                        {formatDateTime(entry.startedAt)}
                      </time>
                      {entry.completedAt ? (
                        <>
                          {' → '}
                          <time dateTime={entry.completedAt}>
                            {formatDateTime(entry.completedAt)}
                          </time>
                        </>
                      ) : (
                        ' · In progress'
                      )}
                      {' · '}
                      {formatBytes(entry.bytesFetched)}
                      {' · '}
                      {formatDurationMs(entry.durationMs)}
                    </p>
                    {entry.errorMessage ? (
                      <p className="mt-3 font-body text-sm text-accent-soft">
                        {entry.errorMessage}
                      </p>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : null}
          </Section>

          <Section
            title="Versions"
            description="Every observed snapshot of this document, newest first."
            actions={
              <Button
                variant="secondary"
                onClick={versions.reload}
                isLoading={versions.status === 'loading'}
              >
                Refresh
              </Button>
            }
          >
            {versions.isInitialLoading ? <SkeletonRows rows={3} /> : null}

            {versions.status === 'error' && versions.errorMessage ? (
              <ErrorState message={versions.errorMessage} onRetry={versions.reload} />
            ) : null}

            {versions.isEmpty ? (
              <EmptyState
                title="No versions observed yet"
                description="Run a manual check or wait for the next scheduled check to record the first snapshot."
              />
            ) : null}

            {versions.data && versions.data.length > 0 ? (
              <ul className="divide-y divide-line border-y border-line">
                {sortVersionsNewestFirst(versions.data).map((version) => (
                  <li key={version.id} className="py-5">
                    <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
                      <span className="font-body text-base text-ink">
                        v{version.versionNumber}
                      </span>
                      <span className="font-body text-sm text-ink-ghost">
                        <time dateTime={version.observedAt}>
                          {formatDateTime(version.observedAt)}
                        </time>
                      </span>
                    </div>
                    <p className="mt-3 break-all font-body text-sm text-ink-ghost">
                      {version.contentHash}
                    </p>
                  </li>
                ))}
              </ul>
            ) : null}
          </Section>

          <Section
            title="Changes"
            description="Detected differences between successive snapshots, in transition order."
            actions={
              <Button
                variant="secondary"
                onClick={changes.reload}
                isLoading={changes.status === 'loading'}
              >
                Refresh
              </Button>
            }
          >
            {changes.isInitialLoading ? <SkeletonRows rows={3} /> : null}

            {changes.status === 'error' && changes.errorMessage ? (
              <ErrorState message={changes.errorMessage} onRetry={changes.reload} />
            ) : null}

            {changes.isEmpty ? (
              <EmptyState
                title="No changes recorded yet"
                description="When two observed snapshots differ, each detected difference is listed here."
              />
            ) : null}

            {changes.data && changes.data.length > 0 ? (
              <ul className="divide-y divide-line border-y border-line">
                {changes.data.map((change) => (
                  <li key={change.id} className="py-5">
                    <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
                      <span className="font-body text-base text-ink">
                        {changeTypeLabel(change.changeType)}
                      </span>
                      <span className="font-body text-sm text-ink-ghost">
                        v{change.versionNumber} · position {change.changeOrder}
                      </span>
                    </div>
                    {change.oldText ? (
                      <p className="mt-3 font-body text-sm text-ink-ghost">
                        <span className="type-label text-ink-faint">Removed: </span>
                        {change.oldText}
                      </p>
                    ) : null}
                    {change.newText ? (
                      <p className="mt-3 font-body text-sm text-ink">
                        <span className="type-label text-ink-faint">Added: </span>
                        {change.newText}
                      </p>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : null}
          </Section>

          <Section
            title="Version diff"
            description="Compare two adjacent snapshots."
          >
            {versions.isInitialLoading ? <SkeletonRows rows={2} /> : null}

            {versions.status === 'error' && versions.errorMessage ? (
              <ErrorState message={versions.errorMessage} onRetry={versions.reload} />
            ) : null}

            {versions.status === 'success' && !hasVersionsForDiff ? (
              <EmptyState
                title="Not enough versions yet"
                description="Record at least two snapshots — run checks until the version history grows — then pick a pair to compare."
              />
            ) : null}

            {hasVersionsForDiff ? (
              <div className="grid grid-cols-1 gap-6 sm:grid-cols-3 sm:items-end">
                <div className="flex flex-col gap-2">
                  <label htmlFor="diff-from" className="type-label text-ink-muted">
                    From version
                  </label>
                  <select
                    id="diff-from"
                    value={effectiveFrom ?? ''}
                    onChange={(event) => setFromVersion(Number(event.target.value))}
                    disabled={isDiffLoading}
                    className="min-h-11 w-full border border-line-strong bg-transparent px-4 py-3 font-body text-base text-ink transition-colors duration-150 ease-standard focus:border-accent-soft focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {versionOptions.map((version) => (
                      <option key={version.id} value={version.versionNumber}>
                        v{version.versionNumber}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="flex flex-col gap-2">
                  <label htmlFor="diff-to" className="type-label text-ink-muted">
                    To version
                  </label>
                  <select
                    id="diff-to"
                    value={effectiveTo ?? ''}
                    onChange={(event) => setToVersion(Number(event.target.value))}
                    disabled={isDiffLoading}
                    className="min-h-11 w-full border border-line-strong bg-transparent px-4 py-3 font-body text-base text-ink transition-colors duration-150 ease-standard focus:border-accent-soft focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {versionOptions.map((version) => (
                      <option key={version.id} value={version.versionNumber}>
                        v{version.versionNumber}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <Button
                    variant="primary"
                    onClick={() => void fetchVersionDiff(effectiveFrom, effectiveTo)}
                    isLoading={isDiffLoading}
                    disabled={isDiffLoading}
                    fullWidth
                  >
                    {isDiffLoading ? 'Loading diff…' : 'Show diff'}
                  </Button>
                </div>
              </div>
            ) : null}

            {isDiffLoading ? <SkeletonRows rows={2} /> : null}

            {diffError ? (
              <div className="mt-6">
                <ErrorState
                  message={diffError}
                  onRetry={() => void fetchVersionDiff(effectiveFrom, effectiveTo)}
                />
              </div>
            ) : null}

            {diffResult ? (
              <div className="mt-6">
                <p className="font-body text-base text-ink">
                  v{diffResult.fromVersion} → v{diffResult.toVersion}
                </p>
                <p className="mt-1 font-body text-sm text-ink-ghost">
                  {diffResult.changes.length}{' '}
                  {diffResult.changes.length === 1 ? 'change' : 'changes'}
                </p>
                {diffResult.changes.length > 0 ? (
                  <ul className="mt-6 divide-y divide-line border-y border-line">
                    {diffResult.changes.map((change) => (
                      <li key={change.id} className="py-5">
                        <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
                          <span className="font-body text-base text-ink">
                            {changeTypeLabel(change.changeType)}
                          </span>
                          <span className="font-body text-sm text-ink-ghost">
                            v{change.versionNumber} · position {change.changeOrder}
                          </span>
                        </div>
                        {change.oldText ? (
                          <p className="mt-3 font-body text-sm text-ink-ghost">
                            <span className="type-label text-ink-faint">Removed: </span>
                            {change.oldText}
                          </p>
                        ) : null}
                        {change.newText ? (
                          <p className="mt-3 font-body text-sm text-ink">
                            <span className="type-label text-ink-faint">Added: </span>
                            {change.newText}
                          </p>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <EmptyState
                    title="No differences in this transition"
                    description="The two snapshots produced no recorded changes."
                  />
                )}
              </div>
            ) : null}
          </Section>
        </>
      ) : null}
    </PageContainer>
  )
}
