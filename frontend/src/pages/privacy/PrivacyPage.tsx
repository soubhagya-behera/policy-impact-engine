import { listPrivacyPreferences } from '../../api/privacy'
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

/**
 * Privacy profile foundation.
 *
 * Reads the real preference surface from `GET /api/v1/me/privacy-preferences`.
 * This phase establishes the shell and the display of server-provided values;
 * editing is wired in a later phase alongside `PUT`, rather than shipping a
 * control that cannot yet persist.
 */
export function PrivacyPage() {
  const preferences = useAsyncData<PrivacyPreference[]>(
    (signal) => listPrivacyPreferences(signal),
    (data) => data.length === 0,
  )

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
          <Button
            variant="secondary"
            onClick={preferences.reload}
            isLoading={preferences.status === 'loading'}
          >
            Refresh
          </Button>
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
                  <SensitivityScale value={preference.effectiveSensitivity} />
                </li>
              ))}
            </ul>

            <p className="mt-6 font-body text-sm text-ink-ghost">
              Editing these values arrives in a later phase.
            </p>
          </>
        ) : null}
      </Section>
    </PageContainer>
  )
}
