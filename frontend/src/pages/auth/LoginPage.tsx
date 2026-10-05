import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { isApiError } from '../../api/errors'
import { toErrorMessage } from '../../api/errors'
import { useAuth } from '../../auth/AuthContext'
import { AuthLayout } from '../../components/layout/AuthLayout'
import { Button, LinkButton } from '../../components/ui/Button'
import { Field } from '../../components/ui/Field'
import { ROUTES } from '../../app/routes'

/** Where to return after a successful sign-in, defaulting to the overview. */
function resolveRedirect(state: unknown): string {
  if (
    state &&
    typeof state === 'object' &&
    'from' in state &&
    typeof (state as { from: unknown }).from === 'string'
  ) {
    const from = (state as { from: string }).from
    // Only allow same-site app paths, never an absolute or protocol URL.
    return from.startsWith('/app') ? from : ROUTES.app
  }
  return ROUTES.app
}

export function LoginPage() {
  const { signIn, status } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  // Already signed in: nothing to do here.
  if (status === 'authenticated') {
    return <Navigate to={resolveRedirect(location.state)} replace />
  }

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setFormError(null)
    setIsSubmitting(true)
    try {
      await signIn(email.trim(), password)
      navigate(resolveRedirect(location.state), { replace: true })
    } catch (error) {
      // The backend returns a uniform 401 for bad credentials, so the message
      // stays deliberately vague here too.
      setFormError(
        isApiError(error) && error.status === 401
          ? 'That email and password combination is not recognised.'
          : toErrorMessage(error),
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <AuthLayout
      title="Sign in"
      subtitle="Access your tracked policies, impact assessments, and recommendations."
      footer={
        <p className="font-body text-sm text-ink-muted">
          No account yet?{' '}
          <Link
            to={ROUTES.register}
            className="text-ink underline underline-offset-4 transition-colors duration-150 ease-standard hover:text-accent-soft"
          >
            Create one
          </Link>
        </p>
      }
    >
      <form onSubmit={(event) => void onSubmit(event)} noValidate>
        <div className="flex flex-col gap-6">
          {formError ? (
            <div
              role="alert"
              className="border border-accent-soft/40 bg-surface px-4 py-3 font-body text-base text-accent-soft"
            >
              {formError}
            </div>
          ) : null}

          <Field
            label="Email"
            type="email"
            name="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@example.com"
          />

          <Field
            label="Password"
            type="password"
            name="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />

          <Button
            type="submit"
            fullWidth
            isLoading={isSubmitting}
          >
            {isSubmitting ? 'Signing in…' : 'Sign in'}
          </Button>
        </div>
      </form>

      <div className="mt-10 border-t border-line pt-8">
        <LinkButton to={ROUTES.home} variant="ghost" fullWidth>
          Back to overview
        </LinkButton>
      </div>
    </AuthLayout>
  )
}
