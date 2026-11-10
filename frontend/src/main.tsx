import { IconContext } from '@phosphor-icons/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MotionConfig } from 'motion/react'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { ApiError } from './api'
import './index.css'
import { setTimeZone } from './format'
import { setupPwa } from './pwa'

setupPwa()

// Mark keyboard navigation so focus rings appear for it, and clear the mark on any tap or click.
const root = document.documentElement
window.addEventListener('keydown', (e) => e.key === 'Tab' && root.setAttribute('data-keyboard', ''), true)
window.addEventListener('pointerdown', () => root.removeAttribute('data-keyboard'), true)

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (count, err) => !(err instanceof ApiError && err.status < 500) && count < 2,
    },
  },
})

// Count days in the server's time zone (TZ) before anything is shown, so dates match the server's totals. Don't hold
// the app up for long: after 1.5 s (or offline with nothing saved) it starts with the device's zone.
const zone = Promise.race([
  fetch('/api/health').then((r) => r.json()).then((h: { timeZone?: string }) => setTimeZone(h.timeZone)),
  new Promise((r) => setTimeout(r, 1500)),
]).catch(() => undefined)

await zone
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* reducedMotion="user": anyone with Reduce Motion on gets fades only, no movement */}
      <MotionConfig reducedMotion="user">
        <IconContext.Provider value={{ weight: 'regular', size: 20 }}>
          <App />
        </IconContext.Provider>
      </MotionConfig>
    </QueryClientProvider>
  </StrictMode>,
)
