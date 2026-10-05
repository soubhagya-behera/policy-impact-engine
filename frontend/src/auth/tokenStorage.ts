/**
 * Browser token storage.
 *
 * Storage decision (documented deliberately):
 *
 *  - The **refresh token** is persisted in `localStorage`. It must survive a
 *    page reload so a reload can silently restore the session, and it is the
 *    only artifact that survives that long.
 *  - The **access token is held in memory only**. It is short-lived, is
 *    re-obtained from the refresh token on load, and keeping it out of
 *    `localStorage` means a stored copy cannot be replayed out of storage
 *    after the tab is closed.
 *  - No token is ever placed in a URL, query parameter, or path segment, so
 *    tokens cannot leak through `Referer`, browser history, or server logs.
 *
 * The backend issues tokens in a JSON body with `Authorization: Bearer`
 * (no cookies, and CORS credentials stay disabled), so no cookie-based
 * alternative is available. This is the strongest protection available to a
 * pure browser client against token theft; it cannot defend against an
 * in-page XSS payload, which is why the CSP and no-inline-script posture
 * matter alongside it.
 */

const REFRESH_TOKEN_KEY = 'pie.refreshToken'
const USER_EMAIL_KEY = 'pie.email'

/** Returns the stored refresh token, or null when signed out. */
export function readRefreshToken(): string | null {
  try {
    return window.localStorage.getItem(REFRESH_TOKEN_KEY)
  } catch {
    // Private-mode or blocked storage: degrade to a session-only login.
    return null
  }
}

export function writeRefreshToken(token: string): void {
  try {
    window.localStorage.setItem(REFRESH_TOKEN_KEY, token)
  } catch {
    // Storage unavailable; the in-memory token still serves this tab.
  }
}

export function clearRefreshToken(): void {
  try {
    window.localStorage.removeItem(REFRESH_TOKEN_KEY)
  } catch {
    // Nothing to do when storage is unavailable.
  }
}

/** Retained so the shell can greet a returning user before refresh resolves. */
export function readStoredEmail(): string | null {
  try {
    return window.localStorage.getItem(USER_EMAIL_KEY)
  } catch {
    return null
  }
}

export function writeStoredEmail(email: string): void {
  try {
    window.localStorage.setItem(USER_EMAIL_KEY, email)
  } catch {
    // Non-fatal.
  }
}

export function clearStoredEmail(): void {
  try {
    window.localStorage.removeItem(USER_EMAIL_KEY)
  } catch {
    // Non-fatal.
  }
}
