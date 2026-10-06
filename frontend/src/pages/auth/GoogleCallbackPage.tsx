import { useEffect, useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { isApiError, toErrorMessage } from '../../api/errors'
import { useAuth } from '../../auth/AuthContext'
import { ROUTES } from '../../app/routes'
import { AuthLayout } from '../../components/layout/AuthLayout'
import { Button, LinkButton } from '../../components/ui/Button'

/**
 * Landing page for the backend Google callback.
 *
 * The URL carries only the short-lived single-use completion `code`
 * (never an access or refresh token). This page exchanges it once for
 * the standard token pair through the existing session machinery and
 * then replaces the location with `/app`, which clears the code from
 * the visible URL and browser history entry.
 */
export function toGoogleCallbackError(error: unknown): string {
  if (isApiError(error)) {
    if (error.status === 409) {
      return 'An account with that email already exists. Sign in with your password instead.'
    }
    if (error.status === 401) {
      return 'This Google sign-in link has expired or was already used. Please try again.'
    }
    if (error.status === 429) {
      return 'Too many attempts. Please wait a moment and try again.'
    }
  }
  return toErrorMessage(error)
}

export function GoogleCallbackPage() {
  const { completeGoogleSignIn, status } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [error, setError] = useState<string | null>(null)
  const [isCompleting, setIsCompleting] = useState(true)

  const code = searchParams.get('code')

  useEffect(() => {
    if (status === 'authenticated') return
    if (!code) {
      setError('This Google sign-in did not return a code. Please try again.')
      setIsCompleting(false)
      return
    }
    let cancelled = false
    void (async () => {
      try {
        await completeGoogleSignIn(code)
        if (!cancelled) navigate(ROUTES.app, { replace: true })
      } catch (failure) {
        if (!cancelled) {
          setError(toGoogleCallbackError(failure))
          setIsCompleting(false)
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [code, completeGoogleSignIn, navigate, status])

  if (status === 'authenticated') {
    return <Navigate to={ROUTES.app} replace />
  }

  return (
    <AuthLayout
      title="Finishing Google sign-in"
      subtitle="Exchanging the one-time code for your session."
      footer={
        <p className="font-body text-sm text-ink-muted">
          <Link
            to={ROUTES.login}
            className="text-ink underline underline-offset-4 transition-colors duration-150 ease-standard hover:text-accent-soft"
          >
            Back to sign in
          </Link>
        </p>
      }
    >
      {error ? (
        <div className="flex flex-col gap-6">
          <div
            role="alert"
            className="border border-accent-soft/40 bg-surface px-4 py-3 font-body text-base text-accent-soft"
          >
            {error}
          </div>
          <LinkButton to={ROUTES.login} variant="secondary" fullWidth>
            Back to sign in
          </LinkButton>
        </div>
      ) : (
        <div className="flex flex-col gap-6">
          <p className="font-body text-base text-ink-muted" role="status">
            {isCompleting
              ? 'Contacting the server…'
              : 'Almost there…'}
          </p>
          <Button type="button" fullWidth isLoading>
            Finishing sign-in…
          </Button>
        </div>
      )}
    </AuthLayout>
  )
}
