import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/**
 * The backend ships its own CORS allow-list and denies cross-origin browser
 * calls by default (see `CorsProperties` in the Spring backend). To keep the
 * dev experience working without loosening backend security, the dev server
 * proxies `/api` to the local backend instead of issuing cross-origin calls.
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
    },
  },
})
