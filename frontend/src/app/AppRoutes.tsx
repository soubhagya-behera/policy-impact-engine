import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from '../components/layout/AppShell'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { ActivityPage } from '../pages/activity/ActivityPage'
import { LoginPage } from '../pages/auth/LoginPage'
import { GoogleCallbackPage } from '../pages/auth/GoogleCallbackPage'
import { RegisterPage } from '../pages/auth/RegisterPage'
import { DashboardPage } from '../pages/dashboard/DashboardPage'
import { HomePage } from '../pages/HomePage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { PoliciesPage } from '../pages/policies/PoliciesPage'
import { PolicyDetailPage } from '../pages/policies/PolicyDetailPage'
import { PrivacyPage } from '../pages/privacy/PrivacyPage'
import { ROUTES } from './routes'

/**
 * Route table.
 *
 * Public routes sit outside the shell; everything under `/app` is wrapped in
 * {@link ProtectedRoute} and rendered inside {@link AppShell}, so
 * authentication and navigation are declared once here rather than in each
 * page. `*` resolves to the 404 page for every unknown path.
 */
export function AppRoutes() {
  return (
    <Routes>
      {/* Public */}
      <Route path={ROUTES.home} element={<HomePage />} />
      <Route path={ROUTES.login} element={<LoginPage />} />
      <Route path={ROUTES.register} element={<RegisterPage />} />
      <Route path={ROUTES.googleCallback} element={<GoogleCallbackPage />} />

      {/* Authenticated application */}
      <Route
        path={ROUTES.app}
        element={
          <ProtectedRoute>
            <AppShell />
          </ProtectedRoute>
        }
      >
        {/* Index route so /app resolves to the overview. */}
        <Route index element={<DashboardPage />} />
        <Route path="policies" element={<PoliciesPage />} />
        <Route path="policies/:policyId" element={<PolicyDetailPage />} />
        <Route path="privacy" element={<PrivacyPage />} />
        <Route path="activity" element={<ActivityPage />} />
      </Route>

      {/* Legacy/alternate entry points that should not 404 loudly. */}
      <Route path="/dashboard" element={<Navigate to={ROUTES.app} replace />} />

      {/* Unknown routes resolve cleanly. */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
