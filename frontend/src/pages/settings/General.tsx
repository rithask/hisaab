import { DeviceMobile, Export, PlusSquare } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { api } from '../../api'
import { isIos, useInstall } from '../../pwa'
import { Button, Card, ErrorText, Field, Input, Notice, Section, Toggle, IconBadge } from '../../components/ui'
import { useMoneyMutation, useSettings } from '../../hooks'

export function GeneralSection() {
  const s = useSettings()
  const [owner, setOwner] = useState('')
  const [msg, setMsg] = useState<string | null>(null)
  useEffect(() => setOwner(s.data?.ownerName ?? ''), [s.data?.ownerName])
  const homeOpt = useMoneyMutation((v: boolean) => api('/settings', { method: 'PUT', body: { showInvestmentsOnHome: v } }))
  const save = useMoneyMutation(() => api('/settings', { method: 'PUT', body: { ownerName: owner } }), () => setMsg('Saved.'))
  return (
    <>
      <Card className="space-y-4 p-5">
        <Field label="Your name, as banks show it" hint="Used to spot money you move to yourself, e.g. UPI to ARJUN RAVI K">
          <Input value={owner} onChange={(e) => setOwner(e.target.value)} />
        </Field>
        <Button onClick={() => save.mutate(undefined)} disabled={save.isPending}>
          Save
        </Button>
        {msg && <Notice>{msg}</Notice>}
        <ErrorText error={save.error} />
      </Card>

      <Section title="Home">
        <Card className="p-4">
          <Toggle checked={s.data?.showInvestmentsOnHome ?? true} onChange={(v) => homeOpt.mutate(v)} label="Show investments on Home" />
          <p className="ink-2 mt-1 text-small">When off, Home's net worth leaves out stocks and funds. They're still on the Invest tab.</p>
        </Card>
      </Section>

      <InstallCard />
    </>
  )
}

/**
 * Add the app to the home screen. Chrome/Edge (Android, desktop) offer a real Install button; Safari on iPhone
 * and iPad installs from the Share menu, so it gets the two steps instead. Hidden once installed.
 */
function InstallCard() {
  const i = useInstall()
  if (i.installed) return null
  const ios = isIos()
  if (!i.canPrompt && !ios) return null
  return (
    <Section title="App">
      <Card className="p-4">
        <div className="flex items-center gap-3">
          <IconBadge icon={DeviceMobile} />
          <div className="min-w-0 flex-1">
            <p className="font-medium">Install Hisaab</p>
            <p className="ink-2 text-small">Opens from your home screen, full screen, and shows your last data when offline.</p>
          </div>
          {i.canPrompt && (
            <Button size="sm" onClick={() => i.prompt()}>
              Install
            </Button>
          )}
        </div>
        {!i.canPrompt && ios && (
          <ol className="ink-2 mt-3 space-y-1.5 border-t border-line pt-3 text-label">
            <li className="flex items-center gap-2">
              1. Tap <Export size={18} className="text-accent" aria-label="Share" /> in Safari's toolbar
            </li>
            <li className="flex items-center gap-2">
              2. Choose <PlusSquare size={18} aria-hidden /> <b className="text-ink">Add to Home Screen</b>
            </li>
          </ol>
        )}
      </Card>
    </Section>
  )
}
