import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { clearSavedData } from './pwa'
import { SIGNED_OUT_KEY } from './pages/Login'
import { api, type Account, type Category, type Feature, type Person, type Settings } from './api'

export const useAccounts = () => useQuery({ queryKey: ['accounts'], queryFn: () => api<Account[]>('/accounts') })
export const useCategories = () => useQuery({ queryKey: ['categories'], queryFn: () => api<Category[]>('/categories'), staleTime: 5 * 60_000 })
export const usePeople = () => useQuery({ queryKey: ['people'], queryFn: () => api<Person[]>('/people') })

/** Mutation that refreshes everything money-related afterwards (balances, dashboard, lists all depend on each other). */
export function useMoneyMutation<TVars, TData = unknown>(fn: (v: TVars) => Promise<TData>, onSuccess?: (d: TData, v: TVars) => void) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: (d, v) => {
      qc.invalidateQueries({ predicate: (q) => q.queryKey[0] !== 'me' })
      onSuccess?.(d, v)
    },
  })
}

export const useSettings = () => useQuery({ queryKey: ['settings'], queryFn: () => api<Settings>('/settings') })

/** Whether an optional feature is switched on (Settings → Features); undefined while settings load. */
export function useFeature(f: Feature): boolean | undefined {
  const s = useSettings()
  return s.data ? (s.data.features?.[f] ?? true) : undefined
}

/** Sign out on this device: end the session, drop saved data and show the sign-in screen. */
export function useSignOut() {
  const qc = useQueryClient()
  return async () => {
    await api('/auth/logout', { method: 'POST' }).catch(() => undefined)
    await clearSavedData()
    try {
      sessionStorage.setItem(SIGNED_OUT_KEY, '1') // don't bounce straight back to the identity provider
    } catch {
      // storage unavailable
    }
    // Mark signed out first so the app switches to the sign-in screen, then drop everything else.
    // (qc.clear() would also remove the "me" query the app is watching, so the switch never happened.)
    qc.setQueryData(['me'], null)
    qc.removeQueries({ predicate: (q) => q.queryKey[0] !== 'me' })
  }
}
