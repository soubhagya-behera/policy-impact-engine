import { BrowserRouter } from 'react-router-dom'
import { AppRoutes } from './app/AppRoutes'
import { AuthProvider } from './auth/AuthContext'

/**
 * Application root: authentication sits above routing so route guards and the
 * API client both observe a live session.
 *
 * `BrowserRouter` needs no extra setup in dev (Vite serves `index.html` for
 * unknown paths). A production host must rewrite unknown paths to
 * `index.html` for deep links to resolve.
 */
export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AuthProvider>
  )
}
