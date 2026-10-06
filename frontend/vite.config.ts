import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/**
 * The backend ships its own CORS allow-list and denies cross-origin browser
 * calls by default (see `CorsProperties` in the Spring backend). To keep the
 * dev experience working without loosening backend security, the dev server
 * proxies `/api` to the local backend instead of issuing cross-origin calls.
 *
 * Google sign-in is a top-level navigation (never fetch/XHR) that bounces
 * between the backend and Google: after `/api/v1/auth/google/start` the
 * browser resolves the backend's relative redirect to
 * `/oauth2/authorization/google` same-origin, so that path — and the
 * `/login/oauth2/code/*` provider callback — must be proxied too.
 *
 * `VITE_API_BASE_URL` overrides this in any environment. Leave it empty to use
 * the same-origin proxy below; set it to an absolute origin (for example
 * `https://api.example.com`) for deployments that do allow the origin.
 */
const DEV_API_PROXY_TARGET = 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: DEV_API_PROXY_TARGET,
        changeOrigin: true,
      },
      '/oauth2': {
        target: DEV_API_PROXY_TARGET,
        changeOrigin: true,
      },
      '/login/oauth2': {
        target: DEV_API_PROXY_TARGET,
        changeOrigin: true,
      },
    },
  },
})
