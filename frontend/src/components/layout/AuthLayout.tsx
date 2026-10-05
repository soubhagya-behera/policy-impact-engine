import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { BrandMark } from '../navigation/BrandMark'
import { ROUTES } from '../../app/routes'

/**
 * Centered frame for the sign-in and registration screens.
 *
 * Deliberately a different shape from `AppShell`: these routes are anonymous,
 * so they use a narrow single column with the wordmark instead of the full
 * application navigation.
 */
export function AuthLayout({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string
  subtitle: string
  children: ReactNode
  footer: ReactNode
}) {
  return (
    <div className="flex min-h-dvh flex-col bg-base">
      <header className="border-b border-line">
        <div className="shell-frame flex h-16 items-center md:h-20">
          <Link
            to={ROUTES.home}
            className="flex min-h-11 items-center"
            aria-label="Policy Impact Engine — home"
          >
            <BrandMark />
          </Link>
        </div>
      </header>

      <main className="flex flex-1 items-center justify-center py-12 md:py-16">
        <div className="shell-frame">
          <div className="mx-auto w-full max-w-md">
            <p className="type-label text-accent-soft">Account</p>
            <h1 className="type-section mt-5 text-ink">{title}</h1>
            <p className="type-body mt-4 text-ink-muted">{subtitle}</p>
            <div className="mt-10">{children}</div>
          </div>
        </div>
      </main>

      <footer className="border-t border-line">
        <div className="shell-frame py-8">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <p className="font-body text-sm text-ink-ghost">
              Policy Impact Engine
            </p>
            {footer}
          </div>
        </div>
      </footer>
    </div>
  )
}
