import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Lock, SignIn } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { api } from '../api'
import { Button, ErrorText, Field, Input, SPRING } from '../components/ui'

interface Options {
  passwordLogin: boolean
  oidc?: { name: string; startUrl: string }
  autoRedirect: boolean
}

/** Set on sign-out so the sign-in page doesn't bounce straight back to the identity provider. */
export const SIGNED_OUT_KEY = 'hisaab:signed-out'

const flag = (k: string) => {
  try {
    return sessionStorage.getItem(k) != null
  } catch {
    return false
  }
}

export default function Login() {
  const qc = useQueryClient()
  const opts = useQuery({ queryKey: ['auth-options'], queryFn: () => api<Options>('/auth/options'), retry: 1 })
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  const params = new URLSearchParams(window.location.search)
  const linking = params.has('link')
  const loginError = params.get('login_error')
  // ?password shows the password form even when it would otherwise auto-redirect.
  const stay = linking || params.has('password') || flag(SIGNED_OUT_KEY) || !!loginError
  // If the options can't be loaded, fall back to the password form rather than an empty screen.
  const o: Options | undefined = opts.data ?? (opts.isError ? { passwordLogin: true, autoRedirect: false } : undefined)
  const redirecting = !!o?.oidc && o.autoRedirect && !stay

  useEffect(() => {
    if (redirecting) window.location.assign(o!.oidc!.startUrl)
  }, [redirecting]) // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      qc.setQueryData(['me'], await api<{ username: string }>('/auth/login', { body: { username, password } }))
      try {
        sessionStorage.removeItem(SIGNED_OUT_KEY)
      } catch {
        // storage unavailable; nothing to clear
      }
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  const startOidc = () => {
    try {
      sessionStorage.removeItem(SIGNED_OUT_KEY)
    } catch {
      // storage unavailable
    }
    window.location.assign(o!.oidc!.startUrl)
  }

  const showPassword = o ? o.passwordLogin || !o.oidc : false

  return (
    <div className="flex min-h-dvh items-center justify-center p-6">
      <motion.div className="w-full max-w-sm" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={SPRING}>
        <div className="mb-8 text-center">
          <img src="/icon.svg" alt="" className="mx-auto mb-5 h-16 w-16 rounded-[18px]" />
          <h1 className="text-[28px] font-bold tracking-[-0.02em]">Hisaab</h1>
          <p className="ink-2 mt-1.5 text-[15px]">{redirecting ? `Taking you to ${o!.oidc!.name}…` : 'Your money, in one place.'}</p>
        </div>

        {linking && <LinkPrompt onDone={(me) => qc.setQueryData(['me'], me)} />}

        {!linking && !redirecting && o && (
          <div className="surface space-y-4 rounded-[20px] p-5">
            {loginError && <p className="anim-pop rounded-xl bg-[color-mix(in_oklab,var(--negative)_12%,transparent)] px-4 py-3 text-sm text-[var(--negative)]">{loginError}</p>}
            {flag(SIGNED_OUT_KEY) && !loginError && <p className="ink-2 text-center text-sm">You're signed out.</p>}

            {o.oidc && (
              <Button className="w-full" onClick={startOidc}>
                <SignIn size={18} /> Sign in with {o.oidc.name}
              </Button>
            )}

            {o.oidc && showPassword && (
              <div className="ink-3 flex items-center gap-3 text-xs">
                <span className="h-px flex-1 bg-[var(--line)]" /> or <span className="h-px flex-1 bg-[var(--line)]" />
              </div>
            )}

            {showPassword && (
              <form onSubmit={submit} className="space-y-4">
                <Field label="Username">
                  <Input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus={!o.oidc} />
                </Field>
                <Field label="Password">
                  <Input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
                </Field>
                <ErrorText error={error} />
                <Button type="submit" variant={o.oidc ? 'secondary' : 'primary'} className="w-full" disabled={busy}>
                  <Lock size={16} /> {busy ? 'Signing in…' : 'Sign in with password'}
                </Button>
              </form>
            )}
          </div>
        )}
      </motion.div>
    </div>
  )
}

interface PendingLink {
  email?: string
  name?: string
  provider: string
  username: string
  passwordRequired: boolean
}

/**
 * First sign-in with the identity provider: ask before linking that account to this app. With password sign-in
 * on, the app password confirms it's really the owner.
 */
function LinkPrompt({ onDone }: { onDone: (me: { username: string }) => void }) {
  const pending = useQuery({ queryKey: ['pending-link'], queryFn: () => api<PendingLink>('/auth/link'), retry: false })
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const leave = () => window.location.assign('/')
  const confirm = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const me = await api<{ username: string }>('/auth/link', { body: { password } })
      window.history.replaceState(null, '', '/')
      onDone(me)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }
  const cancel = async () => {
    await api('/auth/link/cancel', { method: 'POST' }).catch(() => undefined)
    try {
      sessionStorage.setItem(SIGNED_OUT_KEY, '1')
    } catch {
      // storage unavailable
    }
    leave()
  }

  if (pending.isError)
    return (
      <div className="surface space-y-4 rounded-[20px] p-5 text-center">
        <p className="ink-2 text-[15px]">That sign-in has expired. Please sign in again.</p>
        <Button className="w-full" onClick={leave}>
          Back to sign in
        </Button>
      </div>
    )
  const p = pending.data
  if (!p) return null
  return (
    <form onSubmit={confirm} className="surface space-y-4 rounded-[20px] p-5">
      <div>
        <p className="text-[17px] font-semibold">Link your {p.provider} account?</p>
        <p className="ink-2 mt-1.5 text-[15px] leading-relaxed">
          <b className="text-[var(--ink)]">{p.email ?? p.name ?? 'This account'}</b> isn't linked to Hisaab yet. Once linked, you'll sign in with {p.provider} from now on.
        </p>
      </div>
      {p.passwordRequired && (
        <Field label={`App password for “${p.username}”`} hint="Confirms you own this Hisaab.">
          <Input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required autoFocus />
        </Field>
      )}
      <ErrorText error={error} />
      <Button type="submit" className="w-full" disabled={busy || (p.passwordRequired && !password)}>
        {busy ? 'Linking…' : 'Link and sign in'}
      </Button>
      <Button type="button" variant="secondary" className="w-full" onClick={cancel}>
        Don't link
      </Button>
    </form>
  )
}
