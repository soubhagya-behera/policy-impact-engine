import { getImpactSummary } from '../../api/impact'
import { listPolicies } from '../../api/policies'
import { PageContainer } from '../../components/layout/PageContainer'
import { StatFigure } from '../../components/ui/Layout'
import { useAsyncData } from '../../hooks/useAsyncData'
import { maxImpactScoreDisplay } from '../../lib/format'
import type { ImpactSummary, Policy } from '../../api/types'
import { HeroSection } from './HeroSection'
import { LatestImpactSection } from './LatestImpactSection'
import { PoliciesSection } from './PoliciesSection'
import { NotificationsPanel, RecommendationsPanel } from './DashboardPanels'

/**
 * The first real product screen: an editorial hero followed by a structured
 * dashboard built entirely from live backend data. No invented figures — every
 * number below is returned by the API, and a panel with no data says so.
 */
export function DashboardPage() {
  const policies = useAsyncData<Policy[]>(
    (signal) => listPolicies({ page: 0, size: 50 }, signal),
    (data) => data.length === 0,
  )

  const summary = useAsyncData<ImpactSummary>((signal) =>
    getImpactSummary(signal),
  )

  return (
    <PageContainer size="wide">
      <HeroSection />

      {/* Headline figures */}
      <section aria-label="Key figures" className="py-12 md:py-16">
        <div className="grid grid-cols-2 gap-x-6 gap-y-10 lg:grid-cols-4">
          <StatFigure
            value={policies.isInitialLoading ? '—' : (policies.data?.length ?? 0)}
            label="Policies tracked"
          />
          <StatFigure
            value={
              summary.isInitialLoading ? '—' : (summary.data?.totalAssessments ?? 0)
            }
            label="Impact assessments"
          />
          <StatFigure
            value={
              summary.isInitialLoading
                ? '—'
                : (summary.data?.actionableRecommendations ?? 0)
            }
            label="Actions recommended"
            tone="accent"
          />
          <StatFigure
            value={
              summary.isInitialLoading
                ? '—'
                : maxImpactScoreDisplay(summary.data)
            }
            label="Highest impact score"
            tone="info"
          />
        </div>
      </section>

      <LatestImpactSection state={summary} />
      <PoliciesSection state={policies} />
      <RecommendationsPanel />
      <NotificationsPanel />
    </PageContainer>
  )
}
