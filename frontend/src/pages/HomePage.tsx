import { LinkButton } from '../components/ui/Button'
import { PublicNavigation } from '../components/navigation/PublicNavigation'
import { useAuth } from '../auth/AuthContext'
import { ROUTES } from '../app/routes'

/**
 * Public landing page.
 *
 * Presents the product and its value proposition in Policy Impact Engine
 * terms. It deliberately does not imitate the reference marketing page
 * section-for-section; it borrows only the visual language — near-black
 * foundation, oversized display type, hairline rules, flat CTAs.
 */
export function HomePage() {
  const { isAuthenticated } = useAuth()

  return (
    <div className="flex min-h-dvh flex-col bg-base">
      <PublicNavigation />

      <main className="flex-1">
        {/* Hero */}
        <section className="surface-wash border-b border-line">
          <div className="shell-frame py-20 md:py-28 lg:py-36">
            <p className="type-label text-accent-soft">Policy Impact Engine</p>
            <h1 className="type-display mt-6 max-w-5xl text-ink">
              Understand what changed. Know what it means for you.
            </h1>
            <p className="type-body mt-8 max-w-2xl text-ink-muted">
              Track policy changes, see their privacy impact, and know what
              action to take.
            </p>

            <div className="mt-10 flex flex-col gap-3 sm:flex-row">
              <LinkButton to={isAuthenticated ? ROUTES.app : ROUTES.register}>
                {isAuthenticated ? 'Go to overview' : 'Get started'}
              </LinkButton>
              <LinkButton to={ROUTES.login} variant="secondary">
                Sign in
              </LinkButton>
            </div>
          </div>
        </section>

        {/* What it does */}
        <section className="border-b border-line">
          <div className="shell-frame py-16 md:py-24">
            <h2 className="type-section max-w-3xl text-ink">
              Policy documents change quietly. Their consequences rarely do.
            </h2>

            <div className="mt-14 grid grid-cols-1 gap-x-10 gap-y-12 md:grid-cols-3">
              <article className="border-t border-line pt-6">
                <p className="type-label text-accent-soft">01</p>
                <h3 className="type-subheading mt-4 text-ink">Watch</h3>
                <p className="type-body mt-3 text-ink-muted">
                  Point the engine at a policy URL. It is checked on a schedule
                  and every new version is detected automatically.
                </p>
              </article>

              <article className="border-t border-line pt-6">
                <p className="type-label text-accent-soft">02</p>
                <h3 className="type-subheading mt-4 text-ink">Assess</h3>
                <p className="type-body mt-3 text-ink-muted">
                  Each change is scored against the privacy concepts you told
                  us you care about, weighted by your own sensitivity.
                </p>
              </article>

              <article className="border-t border-line pt-6">
                <p className="type-label text-accent-soft">03</p>
                <h3 className="type-subheading mt-4 text-ink">Act</h3>
                <p className="type-body mt-3 text-ink-muted">
                  Get a concrete recommendation — delete your data, opt out of
                  sharing, or review a setting — when it is actually warranted.
                </p>
              </article>
            </div>
          </div>
        </section>

        {/* Closing CTA */}
        <section className="surface-wash">
          <div className="shell-frame py-16 md:py-24">
            <h2 className="type-section max-w-3xl text-ink">
              Know what a policy change means for you.
            </h2>
            <p className="type-body mt-6 max-w-2xl text-ink-muted">
              Create an account to start tracking the documents that affect
              you.
            </p>
            <div className="mt-10 flex flex-col gap-3 sm:flex-row">
              <LinkButton to={isAuthenticated ? ROUTES.app : ROUTES.register}>
                {isAuthenticated ? 'Go to overview' : 'Create your account'}
              </LinkButton>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t border-line">
        <div className="shell-frame py-10">
          <p className="font-body text-sm text-ink-ghost">
            Policy Impact Engine
          </p>
        </div>
      </footer>
    </div>
  )
}
