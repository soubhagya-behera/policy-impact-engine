import { Link, Outlet } from 'react-router-dom'
import { AppNavigation } from '../navigation/AppNavigation'
import { ROUTES } from '../../app/routes'

/**
 * Authenticated application shell: sticky navigation, the routed view, and a
 * restrained utility footer.
 *
 * `min-height` on the wrapper keeps short pages from leaving the footer
 * stranded mid-viewport on tall displays.
 */
export function AppShell() {
  return (
    <div className="flex min-h-dvh flex-col bg-base">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-[60] focus:bg-ink focus:px-4 focus:py-2 focus:font-body focus:text-base"
      >
        Skip to content
      </a>

      <AppNavigation />

      <main id="main-content" className="flex-1">
        <Outlet />
      </main>

      <SiteFooter />
    </div>
  )
}

/**
 * Utility footer. Deliberately quiet: hairline top rule, muted text, and only
 * the links that are real routes in this product.
 */
export function SiteFooter() {
  return (
    <footer className="border-t border-line">
      <div className="shell-frame py-10">
        <div className="flex flex-col gap-6 md:flex-row md:items-center md:justify-between">
          <div>
            <p className="font-display text-base text-ink-muted">
              Policy Impact Engine
            </p>
            <p className="mt-2 max-w-prose font-body text-sm text-ink-ghost">
              Track policy changes, see their privacy impact, and know what
              action to take.
            </p>
          </div>

          <nav aria-label="Footer">
            <ul className="flex flex-wrap gap-x-8 gap-y-3">
              <li>
                <Link
                  to={ROUTES.policies}
                  className="inline-flex min-h-11 items-center font-body text-sm text-ink-muted transition-colors duration-150 ease-standard hover:text-ink"
                >
                  Policies
                </Link>
              </li>
              <li>
                <Link
                  to={ROUTES.privacy}
                  className="inline-flex min-h-11 items-center font-body text-sm text-ink-muted transition-colors duration-150 ease-standard hover:text-ink"
                >
                  Privacy Profile
                </Link>
              </li>
              <li>
                <Link
                  to={ROUTES.activity}
                  className="inline-flex min-h-11 items-center font-body text-sm text-ink-muted transition-colors duration-150 ease-standard hover:text-ink"
                >
                  Activity
                </Link>
              </li>
            </ul>
          </nav>
        </div>
      </div>
    </footer>
  )
}
