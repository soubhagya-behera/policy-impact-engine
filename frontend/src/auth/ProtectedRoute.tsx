import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from './AuthContext'

/**
 * Gate for authenticated routes.
 *
 * While session restoration is in flight the component renders nothing
 * navigable, so a hard refresh on a protected URL never flashes the login
 * screen before the stored refresh token resolves. Once settled, an anonymous
 * visitor is sent to `/login` with the attempted location preserved in router
 * state, so they land back where they meant to go after signing in.
 */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'restoring') {
    return (
      <div
        className="flex min-h-[60vh] items-center justify-center"
        role="status"
        aria-live="polite"
        aria-busy="true"
      >
        <span className="type-label text-ink-faint">Restoring session…</span>
      </div>
    )
  }

  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <>{children}</>
}
