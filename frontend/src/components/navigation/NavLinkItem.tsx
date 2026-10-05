import { NavLink } from 'react-router-dom'
import type { RoutePath } from '../../app/routes'
import { cn } from '../../lib/cn'

/**
 * Primary navigation definition.
 *
 * Labels are Policy Impact Engine product terms. The underline treatment
 * mirrors the reference: a 3px bar that scales in from one edge on hover and
 * focus, over a soft purple block wash.
 */
export interface NavItem {
  to: RoutePath
  label: string
}

export const PRIMARY_NAV: readonly NavItem[] = [
  { to: '/app', label: 'Overview' },
  { to: '/app/policies', label: 'Policies' },
  { to: '/app/privacy', label: 'Privacy Profile' },
  { to: '/app/activity', label: 'Activity' },
]

export function NavLinkItem({
  to,
  label,
  className,
  onNavigate,
}: NavItem & { className?: string; onNavigate?: () => void }) {
  return (
    <NavLink
      to={to}
      end={to === '/app'}
      onClick={onNavigate}
      className={({ isActive }) =>
        cn(
          'group relative inline-flex min-h-11 items-center px-3 py-2',
          'font-body text-base leading-6 tracking-[0.01em]',
          'transition-colors duration-150 ease-standard',
          isActive ? 'text-ink' : 'text-ink-muted hover:text-ink',
          className,
        )
      }
    >
      {({ isActive }) => (
        <>
          {/* Soft wash behind the label, scaling in from the right. */}
          <span
            aria-hidden="true"
            className={cn(
              'pointer-events-none absolute -left-1 -top-1 h-[calc(100%+8px)] w-[calc(100%+8px)] origin-right scale-x-0',
              'bg-accent/25 transition-transform duration-300 ease-global',
              'group-hover:origin-left group-hover:scale-x-100',
              'group-focus-visible:origin-left group-focus-visible:scale-x-100',
              isActive && 'scale-x-100',
            )}
          />
          <span className="relative z-[1] px-1">{label}</span>
          {/* 3px active/hover underline, matching the reference bar. */}
          <span
            aria-hidden="true"
            className={cn(
              'absolute bottom-0 left-0 h-[3px] w-full origin-bottom scale-y-0 bg-ink',
              'transition-transform duration-300 ease-global',
              'group-hover:scale-y-100 group-focus-visible:scale-y-100',
              isActive && 'scale-y-100',
            )}
          />
        </>
      )}
    </NavLink>
  )
}
