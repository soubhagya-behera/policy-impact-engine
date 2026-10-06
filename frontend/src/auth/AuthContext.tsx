import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import type { ReactNode } from 'react'
import * as authApi from '../api/auth'
import {
  ACCESS_TOKEN_EPOCH_SKEW_SECONDS,
  registerAuthBridge,
} from '../api/client'
import type { TokenResponse } from '../api/types'
import {
  clearRefreshToken,
  clearStoredEmail,
  readRefreshToken,
  readStoredEmail,
  writeRefreshToken,
  writeStoredEmail,
} from './tokenStorage'

/**
 * The single authentication mechanism for the application.
 *
 * All token logic lives here and reaches the API client through one registered
 * `AuthBridge`. No component reads or writes a token, so these invariants hold
 * in exactly one place:
 *
 *  - the access token is memory-only and never persisted
 *  - the refresh token lives only in `localStorage` (see `tokenStorage.ts`)
 *  - a refresh failure clears everything and forces a return to /login
 *  - no token ever appears in a URL
 */

export type AuthStatus =
  /** Restoring a persisted session; protected routes wait on this. */
  | 'restoring'
  | 'authenticated'
  | 'anonymous'

export interface AuthContextValue {
  status: AuthStatus
  email: string | null
  /** True only while restoration is in flight. */
  isRestoring: boolean
  isAuthenticated: boolean
  signIn(email: string, password: string): Promise<void>
  signUp(email: string, password: string): Promise<{ email: string }>
  /**
   * Starts Google sign-in with a top-level navigation to the backend
   * OAuth entry point (never fetch/XHR, so the OAuth state cookie
   * survives the Google redirect).
   */
  signInWithGoogle(): void
  /**
   * Completes Google sign-in by exchanging the one-time completion code
   * for the standard token pair, then applying it as the session.
   * The code is opaque and single-use; no Google token is ever stored.
   */
  completeGoogleSignIn(code: string): Promise<void>
  signOut(): Promise<void>
  /** Revokes every live session for this account on the backend. */
  signOutEverywhere(): Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

interface StoredSession {
  accessToken: string
  refreshToken: string
  /** Epoch seconds; used to refresh slightly before real expiry. */
  accessExpiresAt: number
}

function expiryFrom(response: TokenResponse): number {
  return Math.floor(Date.now() / 1000) + response.expiresIn
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<StoredSession | null>(null)
  const [email, setEmail] = useState<string | null>(() => readStoredEmail())
  const [isRestoring, setIsRestoring] = useState(true)

  // Mirrors `session` so the bridge (a plain object outside React) always reads
  // current values without being rebuilt on every render.
  const sessionRef = useRef<StoredSession | null>(null)
  const mountedRef = useRef(true)

  const applySession = useCallback(
    (next: StoredSession, userEmail: string) => {
      sessionRef.current = next
      setSession(next)
      writeRefreshToken(next.refreshToken)
      writeStoredEmail(userEmail)
      setEmail(userEmail)
    },
    [],
  )

  const clearSession = useCallback(() => {
    sessionRef.current = null
    setSession(null)
    setEmail(null)
    clearRefreshToken()
    clearStoredEmail()
  }, [])

  const performRefresh = useCallback(async (): Promise<string | null> => {
    const stored = readRefreshToken()
    if (!stored) return null

    try {
      const response = await authApi.refresh(stored)
      if (!mountedRef.current) return null
      const next: StoredSession = {
        accessToken: response.accessToken,
        refreshToken: response.refreshToken,
        accessExpiresAt: expiryFrom(response),
      }
      applySession(next, email ?? readStoredEmail() ?? '')
      return next.accessToken
    } catch {
      // Includes the uniform 401 for a reused, revoked, or expired token.
      return null
    }
  }, [applySession, email])

  const hasExpiredAccessToken = useCallback((): boolean => {
    const current = sessionRef.current
    if (!current) return false
    return (
      current.accessExpiresAt - ACCESS_TOKEN_EPOCH_SKEW_SECONDS <=
      Date.now() / 1000
    )
  }, [])

  const invalidate = useCallback(
    (_reason: 'refresh-failed') => {
      clearSession()
    },
    [clearSession],
  )

  // Publish the bridge once. It is the only channel the API client uses for
  // tokens, which keeps refresh behaviour centralized.
  useEffect(() => {
    registerAuthBridge({
      getAccessToken: () => sessionRef.current?.accessToken ?? null,
      getRefreshToken: () => readRefreshToken(),
      hasExpiredAccessToken,
      refresh: performRefresh,
      invalidate,
    })
    return () => registerAuthBridge(null)
  }, [hasExpiredAccessToken, performRefresh, invalidate])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

// Session restoration: a stored refresh token is enough to silently
  // re-establish the session on a hard reload.
  useEffect(() => {
    let cancelled = false
    const stored = readRefreshToken()
    if (!stored) {
      setIsRestoring(false)
      return
    }

    void (async () => {
      try {
        const response = await authApi.refresh(stored)
        if (cancelled) return
        applySession(
          {
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            accessExpiresAt: expiryFrom(response),
          },
          readStoredEmail() ?? '',
        )
      } catch {
        if (!cancelled) clearSession()
      } finally {
        if (!cancelled) setIsRestoring(false)
      }
    })()

    return () => {
      cancelled = true
    }
    // Restoration is a one-time initialisation step, not a state sync, so it
    // intentionally runs once on mount.
  }, [applySession, clearSession])

  const signIn = useCallback(
    async (userEmail: string, password: string) => {
      const response = await authApi.login({ email: userEmail, password })
      applySession(
        {
          accessToken: response.accessToken,
          refreshToken: response.refreshToken,
          accessExpiresAt: expiryFrom(response),
        },
        userEmail,
      )
    },
    [applySession],
  )

  const signUp = useCallback(async (userEmail: string, password: string) => {
    // Registration issues no tokens; the user signs in immediately after.
    const response = await authApi.register({ email: userEmail, password })
    return { email: response.email }
  }, [])

  const signInWithGoogle = useCallback(() => {
    // Top-level navigation, not fetch: the OAuth flow leaves this page
    // for Google and returns through the backend callback.
    window.location.assign(authApi.googleStartUrl())
  }, [])

  const completeGoogleSignIn = useCallback(
    async (code: string) => {
      const response = await authApi.completeGoogleSignIn(code)
      // The completion response carries no email (it is the standard
      // token pair), so keep any previously stored address for the
      // greeting; the session itself depends only on the tokens.
      const userEmail = readStoredEmail() ?? ''
      applySession(
        {
          accessToken: response.accessToken,
          refreshToken: response.refreshToken,
          accessExpiresAt: expiryFrom(response),
        },
        userEmail,
      )
    },
    [applySession],
  )

  const signOut = useCallback(async () => {
    const stored = readRefreshToken()
    // Clear locally even if the network call fails, so the shell can never get
    // stuck showing a signed-in view with no usable session.
    clearSession()
    if (!stored) return
    try {
      await authApi.logout({ refreshToken: stored })
    } catch {
      // The backend treats logout as idempotent; nothing to recover here.
    }
  }, [clearSession])

  const signOutEverywhere = useCallback(async () => {
    try {
      await authApi.logoutAll()
    } finally {
      // Clear regardless: the call may legitimately fail if the session
      // already died, and the user asked to sign out.
      clearSession()
    }
  }, [clearSession])

  const value = useMemo<AuthContextValue>(
    () => ({
      status: isRestoring
        ? 'restoring'
        : session
          ? 'authenticated'
          : 'anonymous',
      email,
      isRestoring,
      isAuthenticated: session !== null,
      signIn,
      signUp,
      signInWithGoogle,
      completeGoogleSignIn,
      signOut,
      signOutEverywhere,
    }),
    [
      isRestoring,
      session,
      email,
      signIn,
      signUp,
      signInWithGoogle,
      completeGoogleSignIn,
      signOut,
      signOutEverywhere,
    ],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

/** Access the auth context. Throws if used outside {@link AuthProvider}. */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
