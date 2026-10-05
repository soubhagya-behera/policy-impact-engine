import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { ROUTES } from '../../app/routes'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { useMobileMenu } from '../../hooks/useMobileMenu'
import { BrandMark } from './BrandMark'
import { MenuTrigger } from './MenuTrigger'
import { NavLinkItem, PRIMARY_NAV } from './NavLinkItem'

const PANEL_ID = 'mobile-navigation'

/**
 * Sticky application navigation.
 *
 * Desktop (>= 1024px) shows the full horizontal nav with the user area. Below
 * that it collapses behind an accessible menu button. The mobile panel is a
 * real `<nav>` with `aria-expanded`/`aria-controls`; Escape handling, focus
 * restoration, and the body-scroll lock live in `useMobileMenu`, shared with
 * the public header so the two cannot drift apart. Motion is transform/opacity
 * only, and reduced motion is handled globally in `styles/index.css`.
 */
export function AppNavigation() {
  const isDesktop = useMediaQuery('(min-width: 1024px)')
  const { isOpen, toggle, close, triggerRef } = useMobileMenu(isDesktop)
  const { email, signOut } = useAuth()

  return (
    <header className="sticky top-0 z-50 border-b border-line bg-base/95 backdrop-blur-sm">
      <div className="shell-frame">
        <div className="flex h-16 items-center justify-between gap-4 md:h-20">
          <Link
            to={ROUTES.app}
            className="flex min-h-11 items-center"
            aria-label="Policy Impact Engine — Overview"
          >
            <BrandMark />
          </Link>

          {/* Desktop navigation */}
          <nav aria-label="Primary" className="hidden items-center gap-1 lg:flex">
            {PRIMARY_NAV.map((item) => (
              <NavLinkItem key={item.to} {...item} />
            ))}
          </nav>

          <div className="hidden items-center gap-6 lg:flex">
            {email ? (
              <span
                className="max-w-[220px] truncate font-body text-base text-ink-muted"
                title={email}
              >
                {email}
              </span>
            ) : null}
            <Link
              to={ROUTES.privacy}
              className="inline-flex min-h-11 items-center px-2 font-body text-base text-ink-muted transition-colors duration-150 ease-standard hover:text-ink"
            >
              Profile
            </Link>
            <button
              type="button"
              onClick={() => void signOut()}
              className="inline-flex min-h-11 items-center border border-line-strong px-4 font-body text-base text-ink transition-colors duration-150 ease-standard hover:border-ink hover:bg-surface"
            >
              Logout
            </button>
          </div>

          {/* Mobile menu trigger */}
          <MenuTrigger
            isOpen={isOpen}
            onToggle={toggle}
            controlsId={PANEL_ID}
            buttonRef={triggerRef}
          />
        </div>
      </div>

      {/* Mobile navigation panel */}
      <div
        id={PANEL_ID}
        hidden={!isOpen}
        className="border-t border-line bg-base lg:hidden"
      >
        <nav aria-label="Primary (mobile)" className="shell-frame py-4">
          <ul className="flex flex-col">
            {PRIMARY_NAV.map((item) => (
              <li key={item.to} className="border-b border-line last:border-b-0">
                <NavLinkItem
                  {...item}
                  onNavigate={close}
                  className="w-full px-0 py-4"
                />
              </li>
            ))}
          </ul>

          <div className="mt-6 flex flex-col gap-3">
            {email ? (
              <p className="truncate font-body text-base text-ink-muted">
                {email}
              </p>
            ) : null}
            <NavLinkItem
              to={ROUTES.privacy}
              label="Profile"
              className="w-full px-0"
              onNavigate={close}
            />
            <button
              type="button"
              onClick={() => {
                close()
                void signOut()
              }}
              className="inline-flex min-h-11 w-full items-center justify-center border border-line-strong font-body text-base text-ink transition-colors duration-150 ease-standard hover:border-ink"
            >
              Logout
            </button>
          </div>
        </nav>
      </div>
    </header>
  )
}
