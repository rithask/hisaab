import { useEffect, useReducer, useSyncExternalStore } from 'react'

/** Installing the app (PWA): the service worker, the browser's install offer, offline state. */

type InstallEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }> }
let offer: InstallEvent | null = null
const changed = new Set<() => void>()
const notify = () => changed.forEach((f) => f())

export function setupPwa() {
  // Only in built apps: in development the service worker would keep serving stale files.
  if (import.meta.env.PROD && 'serviceWorker' in navigator)
    window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => undefined))
  // Chrome and Edge offer installation with this event; keep it so Settings can show an Install button.
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault()
    offer = e as InstallEvent
    notify()
  })
  window.addEventListener('appinstalled', () => {
    offer = null
    notify()
  })
}

/** Running from the home screen rather than a browser tab. */
export const isInstalled = () => window.matchMedia('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true
/** iPhone and iPad (including iPads that report themselves as a Mac) install through Safari's Share menu instead. */
export const isIos = () => /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)

export function useInstall() {
  const [, rerender] = useReducer((x: number) => x + 1, 0)
  useEffect(() => {
    changed.add(rerender)
    return () => void changed.delete(rerender)
  }, [])
  return {
    installed: isInstalled(),
    canPrompt: offer != null,
    async prompt() {
      if (!offer) return
      await offer.prompt()
      await offer.userChoice
      offer = null
      notify()
    },
  }
}

/** Forget the data saved for offline use (on sign-out). */
export async function clearSavedData() {
  try {
    await caches.delete('hisaab-data')
  } catch {
    // no Cache Storage (old browser, private mode)
  }
  navigator.serviceWorker?.controller?.postMessage('clear-data')
}

const subscribe = (f: () => void) => {
  window.addEventListener('online', f)
  window.addEventListener('offline', f)
  return () => {
    window.removeEventListener('online', f)
    window.removeEventListener('offline', f)
  }
}
export const useOnline = () => useSyncExternalStore(subscribe, () => navigator.onLine)
