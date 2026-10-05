import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { isApiError, toErrorMessage } from '../../api/errors'
import { useAuth } from '../../auth/AuthContext'
import { AuthLayout } from '../../components/layout/AuthLayout'
import { Button, LinkButton } from '../../components/ui/Button'
import { Field } from '../../components/ui/Field'
import { ROUTES } from '../../app/routes'

/** Mirrors the backend's `RegisterRequest` size constraint. */
const MIN_PASSWORD_LENGTH = 8

export function RegisterPage() {
  const { signUp, signIn, status } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  if (status === 'authenticated') {
    return <Navigate to={ROUTES.app} replace />
  }

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setFormError(null)
    setFieldErrors({})

    // Client-side guard mirrors the server constraint so the user gets an
    // immediate answer instead of a round trip.
    if (password.length < MIN_PASSWORD_LENGTH) {
      setFieldErrors({
        password: `Use at least ${MIN_PASSWORD_LENGTH} characters.`,
      })
      return
    }

    setIsSubmitting(true)
    try {
      const created = await signUp(email.trim(), password)
      // Registration issues no tokens, so sign in immediately and land on the
      // overview the user came for.
      await signIn(created.email, password)
      navigate(ROUTES.app, { replace: true })
    } catch (error) {
      if (isApiError(error)) {
        setFieldErrors(error.fieldErrors)
        if (error.status === 409) {
          setFormError('An account with that email already exists.')
        }
      }
      if (!isApiError(error) || error.status !== 409) {
        setFormError(toErrorMessage(error))
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Start tracking the policies that affect you and see what each change means."
      footer={
        <p className="font-body text-sm text-ink-muted">
          Already registered?{' '}
          <Link
            to={ROUTES.login}
            className="text-ink underline underline-offset-4 transition-colors duration-150 ease-standard hover:text-accent-soft"
          >
            Sign in
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
            errorMessage={fieldErrors['email']}
          />

          <Field
            label="Password"
            type="password"
            name="password"
            autoComplete="new-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
            errorMessage={fieldErrors['password']}
          />

          <Button type="submit" fullWidth isLoading={isSubmitting}>
            {isSubmitting ? 'Creating account…' : 'Create account'}
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
