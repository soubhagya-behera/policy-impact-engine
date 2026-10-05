import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { ROUTES } from '../../app/routes'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { useMobileMenu } from '../../hooks/useMobileMenu'
import { BrandMark } from './BrandMark'
import { MenuTrigger } from './MenuTrigger'
import { LinkButton } from '../ui/Button'

const PANEL_ID = 'public-mobile-navigation'

/**
 * Public landing-page header.
 *
 * The desktop composition (brand left, account actions right) is unchanged.
 * Below the project's existing `lg` (1024px) breakpoint the actions move into
 * the same compact panel pattern the authenticated shell already uses: a
 * single 44px trigger instead of two buttons that would otherwise wrap onto
 * three lines at 375–393px.
 */
export function PublicNavigation() {
  const { isAuthenticated } = useAuth()
  const isDesktop = useMediaQuery('(min-width: 1024px)')
  const { isOpen, toggle, close, triggerRef } = useMobileMenu(isDesktop)

  return (
    <header className="border-b border-line">
      <div className="shell-frame flex h-16 items-center justify-between gap-4 md:h-20">
        <Link
          to={ROUTES.home}
          className="flex min-h-11 shrink-0 items-center"
          aria-label="Policy Impact Engine — home"
        >
          <BrandMark />
        </Link>

        {/* Desktop account actions — unchanged from the previous header. */}
        <nav aria-label="Account" className="hidden items-center gap-3 lg:flex">
          {isAuthenticated ? (
            <LinkButton to={ROUTES.app}>Go to overview</LinkButton>
          ) : (
            <>
              <LinkButton to={ROUTES.login} variant="ghost">
                Sign in
              </LinkButton>
              <LinkButton to={ROUTES.register}>Create account</LinkButton>
            </>
          )}
        </nav>

        <MenuTrigger
          isOpen={isOpen}
          onToggle={toggle}
          controlsId={PANEL_ID}
          buttonRef={triggerRef}
        />
      </div>

      {/* Compact mobile panel, mirroring the application shell's treatment. */}
      <div
        id={PANEL_ID}
        hidden={!isOpen}
        className="border-t border-line bg-base lg:hidden"
      >
        <nav aria-label="Account (mobile)" className="shell-frame py-4">
          <div className="flex flex-col gap-3">
            {isAuthenticated ? (
              <LinkButton to={ROUTES.app} onClick={close} fullWidth>
                Go to overview
              </LinkButton>
            ) : (
              <>
                <LinkButton
                  to={ROUTES.login}
                  variant="secondary"
                  onClick={close}
                  fullWidth
                >
                  Sign in
                </LinkButton>
                <LinkButton to={ROUTES.register} onClick={close} fullWidth>
                  Create account
                </LinkButton>
              </>
            )}
          </div>
        </nav>
      </div>
    </header>
  )
}
