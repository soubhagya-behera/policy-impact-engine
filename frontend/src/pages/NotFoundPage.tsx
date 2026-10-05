import { Link } from 'react-router-dom'
import { PageContainer } from '../components/layout/PageContainer'
import { LinkButton } from '../components/ui/Button'
import { useAuth } from '../auth/AuthContext'
import { ROUTES } from '../app/routes'

/**
 * Catch-all route.
 *
 * Resolves cleanly for any unknown path rather than leaving a blank view: it
 * explains that the page does not exist and offers a route back into the app.
 */
export function NotFoundPage() {
  const { isAuthenticated } = useAuth()
  const home = isAuthenticated ? ROUTES.app : ROUTES.home

  return (
    <PageContainer>
      <div className="mx-auto max-w-2xl py-12 md:py-20">
        <p className="type-label text-accent-soft">404</p>
        <h1 className="type-section mt-6 text-ink">Page not found</h1>
        <p className="type-body mt-6 text-ink-muted">
          The page you were looking for does not exist, or it has moved.
        </p>

        <div className="mt-10 flex flex-col gap-3 sm:flex-row">
          <LinkButton to={home}>Go back</LinkButton>
          <LinkButton to={ROUTES.policies} variant="secondary">
            View policies
          </LinkButton>
        </div>

        <p className="mt-12 font-body text-sm text-ink-ghost">
          Think this is a mistake?{' '}
          <Link
            to={ROUTES.home}
            className="text-ink-muted underline underline-offset-4 transition-colors duration-150 ease-standard hover:text-accent-soft"
          >
            Return to the home page
          </Link>
          .
        </p>
      </div>
    </PageContainer>
  )
}
