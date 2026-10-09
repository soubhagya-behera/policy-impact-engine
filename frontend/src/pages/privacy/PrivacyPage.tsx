import { useState } from 'react'
import { listPrivacyPreferences, updatePrivacyPreferences } from '../../api/privacy'
import { toErrorMessage } from '../../api/errors'
import {
  EmptyState,
  ErrorState,
  SkeletonRows,
} from '../../components/ui/AsyncState'
import { Button } from '../../components/ui/Button'
import { PageContainer } from '../../components/layout/PageContainer'
import { PageHeader, Section } from '../../components/ui/Layout'
import { useAsyncData } from '../../hooks/useAsyncData'
import type { PrivacyPreference } from '../../api/types'
import { SensitivityScale } from './SensitivityScale'

/** Backend sensitivity range is 0-5 inclusive. */
export const SENSITIVITY_LEVELS = [0, 1, 2, 3, 4, 5] as const

/**
 * Type guard for a submittable sensitivity: an integer from 0 through 5.
 * The backend rejects anything else with a 400 problem response, so values
 * failing this guard are never sent.
 */
export function isValidSensitivity(value: unknown): value is number {
  return (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 0 &&
    value <= 5
  )
}

/**
 * Readable option text for a sensitivity level, mirroring the descriptors
 * used by {@link SensitivityScale} so the edit control and the read-only
 * display agree on what each level means.
 */
export function sensitivityChoiceLabel(level: number): string {
  if (level === 0) return '0 — Not sensitive'
  if (level <= 2) return `${level} — Low sensitivity`
  if (level <= 4) return `${level} — Medium sensitivity`
  return '5 — High sensitivity'
}

/**
 * Collects only the user's changed preferences for the merge `PUT`: entries
 * whose draft value differs from the loaded effective sensitivity. Invalid
 * values and unknown concept codes are excluded rather than sent.
 */
export function changedPreferences(
  original: PrivacyPreference[],
  draft: Record<string, number>,
): Record<string, number> {
  const changes: Record<string, number> = {}
  for (const preference of original) {
    const value = draft[preference.conceptCode]
    if (
      value !== undefined &&
      value !== preference.effectiveSensitivity &&
      isValidSensitivity(value)
    ) {
      changes[preference.conceptCode] = value
    }
  }
  return changes
}

/**
 * Privacy profile.
 *
 * Reads the preference surface from `GET /api/v1/me/privacy-preferences` and
 * persists edits with the merge `PUT /api/v1/me/privacy-preferences`. The
 * read-only display stays the normal state; editing opens per-concept native
 * selects plus Save/Cancel, submits only changed values, and reloads the real
 * preferences from the backend on success.
 */
export function PrivacyPage() {
  const preferences = useAsyncData<PrivacyPreference[]>(
    (signal) => listPrivacyPreferences(signal),
    (data) => data.length === 0,
  )

  const [isEditing, setIsEditing] = useState(false)
  const [draft, setDraft] = useState<Record<string, number>>({})
  const [saveError, setSaveError] = useState<string | null>(null)
  const [isSaving, setIsSaving] = useState(false)

  const loaded = preferences.data ?? []
  const changes = changedPreferences(loaded, draft)
  const changeCount = Object.keys(changes).length

  function startEditing() {
    const initial: Record<string, number> = {}
    for (const preference of loaded) {
      initial[preference.conceptCode] = preference.effectiveSensitivity
    }
    setDraft(initial)
    setSaveError(null)
    setIsEditing(true)
  }

  function cancelEditing() {
    setIsEditing(false)
    setDraft({})
    setSaveError(null)
  }

  async function handleSave() {
    if (isSaving || changeCount === 0) return
    const payload = changedPreferences(loaded, draft)
    if (Object.keys(payload).length === 0) return
    setIsSaving(true)
    setSaveError(null)
    try {
      await updatePrivacyPreferences({ preferences: payload })
      setIsEditing(false)
      setDraft({})
      preferences.reload()
    } catch (error) {
      // Edits are preserved in `draft` so the user can retry unchanged.
      setSaveError(toErrorMessage(error))
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <PageContainer>
      <PageHeader
        label="Privacy Profile"
        title="How sensitive your data is to you"
        description="Your sensitivity settings decide which privacy concepts the engine weighs most when it assesses a policy change."
      />

      <Section
        title="Sensitivity by concept"
        description="Values you have not set fall back to the default, shown below."
        actions={
          isEditing ? (
            <>
              <Button
                variant="secondary"
                onClick={cancelEditing}
                disabled={isSaving}
              >
                Cancel
              </Button>
              <Button
                onClick={() => void handleSave()}
                isLoading={isSaving}
                disabled={isSaving || changeCount === 0}
              >
                {isSaving ? 'Saving…' : 'Save'}
              </Button>
            </>
          ) : (
            <>
              <Button
                variant="secondary"
                onClick={startEditing}
                disabled={preferences.data === null}
              >
                Edit
              </Button>
              <Button
                variant="secondary"
                onClick={preferences.reload}
                isLoading={preferences.status === 'loading'}
              >
                Refresh
              </Button>
            </>
          )
        }
      >
        {preferences.isInitialLoading ? <SkeletonRows rows={4} /> : null}

        {preferences.status === 'error' && preferences.errorMessage ? (
          <ErrorState
            message={preferences.errorMessage}
            onRetry={preferences.reload}
          />
        ) : null}

        {preferences.isEmpty ? (
          <EmptyState
            title="No privacy concepts available"
            description="Once the privacy vocabulary is loaded for your account, each concept and its sensitivity will be listed here."
          />
        ) : null}

        {preferences.data && preferences.data.length > 0 ? (
          <>
            <ul className="divide-y divide-line border-y border-line">
              {preferences.data.map((preference) => (
                <li
                  key={preference.conceptCode}
                  className="flex flex-col gap-3 py-5 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0">
                    <p className="font-body text-base text-ink">
                      {preference.label}
                    </p>
                    <p className="mt-1 font-body text-sm text-ink-ghost">
                      {preference.conceptCode}
                      {preference.explicit ? ' · set by you' : ' · default'}
                    </p>
                  </div>
                  {isEditing ? (
                    <select
                      aria-label={`Sensitivity for ${preference.label}`}
                      value={
                        draft[preference.conceptCode] ??
                        preference.effectiveSensitivity
                      }
                      disabled={isSaving}
                      onChange={(event) =>
                        setDraft((previous) => ({
                          ...previous,
                          [preference.conceptCode]: Number(event.target.value),
                        }))
                      }
                      className="min-h-11 shrink-0 border border-line-strong bg-transparent px-3 py-2 font-body text-base text-ink transition-colors duration-150 ease-standard focus:border-accent-soft focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {SENSITIVITY_LEVELS.map((level) => (
                        <option key={level} value={level}>
                          {sensitivityChoiceLabel(level)}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <SensitivityScale value={preference.effectiveSensitivity} />
                  )}
                </li>
              ))}
            </ul>

            {isEditing ? (
              <p className="mt-6 font-body text-sm text-ink-ghost">
                {changeCount === 0
                  ? 'No changes yet. Only changed values are saved.'
                  : `${changeCount} changed value${changeCount === 1 ? '' : 's'} to save.`}
              </p>
            ) : null}

            {isEditing && saveError ? (
              <div
                role="alert"
                className="mt-6 border border-accent-soft/40 bg-surface px-5 py-6"
              >
                <p className="type-subheading text-accent-soft">
                  Could not save your changes
                </p>
                <p className="type-body mt-2 text-ink-secondary">{saveError}</p>
                <Button
                  variant="secondary"
                  onClick={() => void handleSave()}
                  disabled={isSaving}
                  className="mt-6"
                >
                  Try again
                </Button>
              </div>
            ) : null}
          </>
        ) : null}
      </Section>
    </PageContainer>
  )
}
