import { useQuery } from '@tanstack/react-query'
import { ChatText, CloudArrowUp, DeviceMobile, Export, PlusSquare, DownloadSimple, Envelope, GraduationCap, IdentificationCard, Key, X } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { api, type BackupStatus, type Feature, type Learned, type Settings as S, type TokenStatus } from '../api'
import { CopyButton } from '../components/SmsPasteSheet'
import { isIos, useInstall } from '../pwa'
import { Badge, Button, Card, ErrorText, Field, Input, Notice, PageHeader, Section, Segmented, Toggle, Page } from '../components/ui'
import { dateTime } from '../format'
import { useMoneyMutation } from '../hooks'

export default function Settings() {
  const s = useQuery({ queryKey: ['settings'], queryFn: () => api<S>('/settings') })
  const [owner, setOwner] = useState('')
  const [since, setSince] = useState('')
  const [msg, setMsg] = useState<string | null>(null)
  useEffect(() => setOwner(s.data?.ownerName ?? ''), [s.data?.ownerName])
  const homeOpt = useMoneyMutation((v: boolean) => api('/settings', { method: 'PUT', body: { showInvestmentsOnHome: v } }))
  const save = useMoneyMutation(() => api('/settings', { method: 'PUT', body: { ownerName: owner } }), () => setMsg('Saved.'))
  const importOld = useMoneyMutation(() => api<{ fetched: number; processed: number; error?: string }>('/mail/import', { body: { since } }), (r) =>
    setMsg(r.error ? `Import failed: ${r.error}` : `Imported ${r.processed} new of ${r.fetched} emails.`),
  )
  const mail = s.data?.mail

  return (
    <Page>
      <PageHeader back title="Settings" />
      <Card className="space-y-4 p-5">
        <Field label="Your name, as banks show it" hint="Used to spot transfers to yourself, e.g. UPI to ARJUN RAVI K">
          <Input value={owner} onChange={(e) => setOwner(e.target.value)} />
        </Field>
        <Button onClick={() => save.mutate(undefined)} disabled={save.isPending}>
          Save
        </Button>
      </Card>

      <Section title="Home">
        <Card className="p-4">
          <Toggle checked={s.data?.showInvestmentsOnHome ?? true} onChange={(v) => homeOpt.mutate(v)} label="Show investments on Home" />
          <p className="ink-2 mt-1 text-[13px]">When off, Home's net worth leaves out stocks and funds. They're still on the Invest tab.</p>
        </Card>
      </Section>

      <InstallCard />
      <FeaturesCard features={s.data?.features} />
      {s.data?.features?.backups !== false && <BackupsCard />}
      <PocketIdCard />

      <Section title="Mailbox">
        <Card className="p-5">
          <div className="flex items-center gap-3">
            <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)] text-[var(--ink)]">
              <Envelope size={20} />
            </span>
            <div className="flex-1">
              <p className="font-semibold">Alerts mailbox</p>
              <p className="ink-2 text-xs">{mail?.lastSync ? `Last checked ${dateTime(mail.lastSync)}` : 'Never checked'}</p>
            </div>
            {mail?.configured ? <Badge tone="green">connected</Badge> : <Badge tone="amber">not set up</Badge>}
          </div>
          {mail?.lastError && <p className="mt-3 text-sm text-[var(--negative)]">{mail.lastError}</p>}
          {!mail?.configured && (
            <p className="ink-2 mt-3 text-sm leading-relaxed">
              Start the server with <code className="rounded bg-[var(--surface-2)] px-1">MAIL_HOST</code>, <code className="rounded bg-[var(--surface-2)] px-1">MAIL_USERNAME</code> and{' '}
              <code className="rounded bg-[var(--surface-2)] px-1">MAIL_PASSWORD</code>. Gmail: imap.gmail.com · iCloud: imap.mail.me.com, with an app-specific password.
            </p>
          )}
        </Card>
      </Section>

      <SmsForwarding />

      <Section title="Import older emails">
        <Card className="space-y-4 p-5">
          <p className="ink-2 text-sm">Only mail that arrives after setup is read automatically. Pick a start date to pull in older alerts. Balances only count transactions after each account's “as of” date.</p>
          <Field label="Since">
            <Input type="date" value={since} onChange={(e) => setSince(e.target.value)} />
          </Field>
          <Button variant="secondary" disabled={!since || importOld.isPending || !mail?.configured} onClick={() => importOld.mutate(undefined)}>
            {importOld.isPending ? 'Importing…' : 'Import'}
          </Button>
        </Card>
      </Section>
      {msg && (
        <div className="mt-3">
          <Notice>{msg}</Notice>
        </div>
      )}
      <ErrorText error={save.error ?? importOld.error} />
      <AppVersion />
    </Page>
  )
}

/** API token + setup guide for forwarding bank SMS from the phone. */
function SmsForwarding() {
  const status = useQuery({ queryKey: ['ingest-token'], queryFn: () => api<TokenStatus>('/ingest/token') })
  const [fresh, setFresh] = useState<string | null>(null)
  const [guide, setGuide] = useState<'ios' | 'android' | 'curl'>('ios')
  const rotate = useMoneyMutation(() => api<{ token: string }>('/ingest/token', { method: 'POST' }), (r) => setFresh(r.token))
  const revoke = useMoneyMutation(() => api('/ingest/token', { method: 'DELETE' }), () => setFresh(null))
  const url = `${window.location.origin}/api/ingest/sms`
  const st = status.data
  const token = fresh ?? 'hisaab_your_token'

  return (
    <Section title="SMS forwarding">
      <Card className="space-y-4 p-5">
        <div className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)] text-[var(--ink)]">
            <ChatText size={20} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="font-semibold">Bank SMS</p>
            <p className="ink-2 truncate text-xs">
              {st?.enabled ? (st.lastUsedAt ? `Last SMS ${dateTime(st.lastUsedAt)}` : 'Token ready, nothing received yet') : 'For alerts that only come by text message'}
            </p>
          </div>
          {st?.enabled ? <Badge tone="green">on</Badge> : <Badge>off</Badge>}
        </div>

        <div className="rounded-[18px] bg-[var(--surface-2)] p-3">
          <p className="ink-2 text-xs font-medium">Endpoint</p>
          <div className="mt-1 flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate font-mono text-[12px]">POST {url}</code>
            <CopyButton value={url} />
          </div>
        </div>

        {fresh ? (
          <div className="anim-pop rounded-[18px] bg-[var(--accent-soft)] p-3">
            <p className="flex items-center gap-1.5 text-xs font-semibold text-[var(--accent)]">
              <Key size={14} /> Your API token, shown only once
            </p>
            <code className="mt-1.5 block break-all font-mono text-[12.5px] leading-relaxed">{fresh}</code>
            <div className="mt-2">
              <CopyButton value={fresh} label="Copy token" />
            </div>
          </div>
        ) : (
          st?.enabled && (
            <p className="ink-2 text-sm">
              Token ending <b className="font-mono">…{st.hint}</b>, created {st.createdAt ? dateTime(st.createdAt) : ''}. It is stored hashed, so it can't be shown again. Generate a new one if you lost it.
            </p>
          )
        )}

        <div className="flex gap-2">
          <Button className="flex-1" size="sm" disabled={rotate.isPending} onClick={() => (!st?.enabled || confirm('Replace the token? The old one stops working immediately.')) && rotate.mutate(undefined)}>
            <Key size={15} /> {st?.enabled ? 'New token' : 'Generate token'}
          </Button>
          {st?.enabled && (
            <Button variant="danger" size="sm" className="flex-1" disabled={revoke.isPending} onClick={() => confirm('Turn off SMS forwarding? The token stops working.') && revoke.mutate(undefined)}>
              Revoke
            </Button>
          )}
        </div>
        <p className="ink-3 text-xs leading-relaxed">The token can only send SMS to this endpoint; it can't read or change anything else. Repeated wrong tokens are locked out for 15 minutes.</p>
      </Card>

      <Card className="mt-3 p-5">
        <p className="mb-3 font-semibold">Set it up on your phone</p>
        <Segmented3 value={guide} onChange={setGuide} />
        <div className="mt-4 text-sm leading-relaxed">
          {guide === 'ios' && (
            <ol className="ink-2 list-decimal space-y-2 pl-5 marker:font-semibold marker:text-[var(--ink)]">
              <li>
                Open <b className="text-[var(--ink)]">Shortcuts</b> → <b className="text-[var(--ink)]">Automation</b> → <b className="text-[var(--ink)]">New Automation</b> → <b className="text-[var(--ink)]">Message</b>.
              </li>
              <li>
                Set <b className="text-[var(--ink)]">Message Contains</b> to <code className="rounded bg-[var(--surface-2)] px-1">debited</code> (make a second one for <code className="rounded bg-[var(--surface-2)] px-1">credited</code>, or one per bank sender). Choose <b className="text-[var(--ink)]">Run Immediately</b>.
              </li>
              <li>
                Add <b className="text-[var(--ink)]">Get Contents of URL</b>: URL above, Method <b className="text-[var(--ink)]">POST</b>, Header <code className="rounded bg-[var(--surface-2)] px-1">Authorization</code> = <code className="break-words rounded bg-[var(--surface-2)] px-1">Bearer {fresh ? token : '<token>'}</code>.
              </li>
              <li>
                Request Body <b className="text-[var(--ink)]">JSON</b>: <code className="rounded bg-[var(--surface-2)] px-1">text</code> = Shortcut Input › Content, <code className="rounded bg-[var(--surface-2)] px-1">sender</code> = Shortcut Input › Sender.
              </li>
              <li>
                Optional: <b className="text-[var(--ink)]">Get Dictionary Value</b> <code className="rounded bg-[var(--surface-2)] px-1">message</code> → <b className="text-[var(--ink)]">Show Notification</b>, to see what was recorded.
              </li>
            </ol>
          )}
          {guide === 'android' && (
            <ol className="ink-2 list-decimal space-y-2 pl-5 marker:font-semibold marker:text-[var(--ink)]">
              <li>
                Install an SMS-to-webhook app (e.g. <b className="text-[var(--ink)]">SMS Forwarder</b>, MacroDroid or Tasker).
              </li>
              <li>Add a rule for your bank senders (contains HDFCBK, AXISBK, SBI, CANBNK…).</li>
              <li>
                Webhook: POST to the URL above with header <code className="break-all rounded bg-[var(--surface-2)] px-1">Authorization: Bearer {fresh ? token : '<token>'}</code> and JSON body{' '}
                <code className="rounded bg-[var(--surface-2)] px-1">{'{"text": "%body%", "sender": "%from%"}'}</code>.
              </li>
            </ol>
          )}
          {guide === 'curl' && (
            <pre className="overflow-x-auto rounded-[16px] bg-[var(--surface-2)] p-3 font-mono text-[11.5px] leading-relaxed">{`curl -X POST ${url} \
  -H 'Authorization: Bearer ${fresh ? token : '<token>'}' \
  -H 'Content-Type: application/json' \
  -d '{"sender":"AX-HDFCBK","text":"Sent Rs.500.00 From HDFC Bank A/C *2286 To RAHUL On 05/11/26 Ref 531234567890"}'`}</pre>
          )}
        </div>
        <p className="ink-3 mt-4 text-xs leading-relaxed">
          Payments that also arrive by email are recorded once. You can also paste an SMS by hand from <b>Inbox → Paste SMS</b>. Your phone needs to reach this server (e.g. over Tailscale or your public URL).
        </p>
      </Card>
      <LearnedCard />
    </Section>
  )
}

/** Everything the SMS parser has picked up, with a way to make it forget a mistake. */
function LearnedCard() {
  const learned = useQuery({ queryKey: ['sms-learned'], queryFn: () => api<Learned>('/sms/learned') })
  const forgetFormat = useMoneyMutation((id: number) => api(`/sms/formats/${id}`, { method: 'DELETE' }))
  const forgetPayee = useMoneyMutation((id: number) => api(`/sms/payees/${id}`, { method: 'DELETE' }))
  const l = learned.data
  const empty = !l || (l.formats.length === 0 && l.payees.length === 0)
  return (
    <Card className="mt-3 p-5">
      <div className="flex items-center gap-3">
        <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)] text-[var(--ink)]">
          <GraduationCap size={20} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-semibold">What it has learned</p>
          <p className="ink-2 text-xs">
            {l ? `${l.formats.length} message format${l.formats.length === 1 ? '' : 's'} · ${l.payees.length} payee name${l.payees.length === 1 ? '' : 's'} · ${l.senders} sender${l.senders === 1 ? '' : 's'}` : ' '}
          </p>
        </div>
      </div>
      {empty ? (
        <p className="ink-2 mt-4 text-sm leading-relaxed">
          Nothing yet. It learns when you correct an SMS in the Inbox, rename a payee on a transaction, or pick the account for a message it couldn't place.
        </p>
      ) : (
        <div className="mt-4 space-y-4">
          {l!.formats.length > 0 && (
            <div>
              <p className="eyebrow ink-3 mb-2">Message formats</p>
              <ul className="space-y-2">
                {l!.formats.map((f) => (
                  <li key={f.id} className="flex items-start gap-2 rounded-[16px] bg-[var(--surface-2)] p-3">
                    <div className="min-w-0 flex-1">
                      <p className="break-words font-mono text-[11px] leading-relaxed">{f.skeleton}</p>
                      <p className="ink-3 mt-1 text-[11px]">
                        {f.institution} · {f.direction === 'DEBIT' ? 'money out' : 'money in'} · used {f.hits} time{f.hits === 1 ? '' : 's'}
                        {f.lastUsedAt ? ` · last ${dateTime(f.lastUsedAt)}` : ''}
                      </p>
                    </div>
                    <button className="pressable ink-3 flex h-7 w-7 shrink-0 items-center justify-center rounded-full" aria-label="Forget this format" onClick={() => confirm('Forget this format?') && forgetFormat.mutate(f.id)}>
                      <X size={14} weight="bold" />
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {l!.payees.length > 0 && (
            <div>
              <p className="eyebrow ink-3 mb-2">Payee names</p>
              <ul className="space-y-2">
                {l!.payees.map((p) => (
                  <li key={p.id} className="flex items-center gap-2 rounded-[16px] bg-[var(--surface-2)] px-3 py-2.5 text-[13px]">
                    <span className="ink-2 min-w-0 flex-1 truncate font-mono text-[11.5px] uppercase">{p.rawKey}</span>
                    <span className="ink-3">→</span>
                    <span className="min-w-0 flex-1 truncate font-medium">{p.displayName}</span>
                    <button className="pressable ink-3 flex h-7 w-7 shrink-0 items-center justify-center rounded-full" aria-label="Forget this name" onClick={() => confirm('Forget this name?') && forgetPayee.mutate(p.id)}>
                      <X size={14} weight="bold" />
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </Card>
  )
}

function Segmented3({ value, onChange }: { value: 'ios' | 'android' | 'curl'; onChange: (v: 'ios' | 'android' | 'curl') => void }) {
  return <Segmented value={value} onChange={onChange} options={[{ value: 'ios', label: 'iPhone' }, { value: 'android', label: 'Android' }, { value: 'curl', label: 'Other' }]} />
}

const FEATURES: { key: Feature; name: string; hint: string }[] = [
  { key: 'backups', name: 'Automatic backups', hint: 'Nightly copy of everything, kept on the server' },
  { key: 'bills', name: 'Upcoming bills', hint: 'Learned from payments that repeat' },
  { key: 'budgets', name: 'Budgets', hint: 'Monthly limits per category' },
  { key: 'subscriptions', name: 'Subscription watch', hint: 'Price rises, double charges, new and stopped subscriptions' },
  { key: 'unusual', name: 'Unusual activity', hint: 'Double charges, odd amounts, foreign and late-night spends, refunds' },
  { key: 'rewards', name: 'Rewards', hint: 'Cashback and points on your credit cards' },
  { key: 'networth', name: 'Net worth over time', hint: 'Daily snapshot and chart' },
  { key: 'review', name: 'Year in review', hint: 'Your year at a glance' },
  { key: 'search', name: 'Advanced search', hint: 'Date range, amount and sort in Activity' },
]

/** Switch optional features off: they disappear from the app and their background jobs stop. */
function FeaturesCard({ features }: { features?: Record<Feature, boolean> }) {
  const set = useMoneyMutation((change: Partial<Record<Feature, boolean>>) => api('/settings', { method: 'PUT', body: { features: change } }))
  return (
    <Section title="Features">
      <Card className="divide-y divide-[var(--line)] p-0">
        {FEATURES.map((f) => (
          <div key={f.key} className="px-4 py-2.5">
            <Toggle
              checked={features?.[f.key] ?? true}
              onChange={(v) => set.mutate({ [f.key]: v })}
              label={
                <span>
                  <span className="block text-[15px]">{f.name}</span>
                  <span className="ink-2 block text-[12.5px]">{f.hint}</span>
                </span>
              }
            />
          </div>
        ))}
      </Card>
      <p className="ink-3 mt-2 px-1 text-[12.5px]">Turning a feature off hides it everywhere and stops any background work for it. Nothing is deleted.</p>
    </Section>
  )
}

function BackupsCard() {
  const q = useQuery({ queryKey: ['backups'], queryFn: () => api<BackupStatus>('/backups') })
  const run = useMoneyMutation(() => api('/backups', { method: 'POST' }))
  const b = q.data
  const failing = !!b?.lastError && (!b.lastOk || (b.lastErrorAt ?? '') > b.lastOk)
  return (
    <Section title="Backups">
      <Card className="p-4">
        <div className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)]">
            <CloudArrowUp size={20} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="font-medium">Every night at 3:30 am</p>
            <p className="ink-2 truncate text-[13px]">{b?.lastOk ? `Last backup ${dateTime(b.lastOk)}` : 'No backup yet'} · keeps {b?.keep ?? 14}</p>
          </div>
          {failing ? <Badge tone="red">failing</Badge> : b?.lastOk ? <Badge tone="green">ok</Badge> : null}
        </div>
        {failing && <p className="mt-3 rounded-xl bg-[color-mix(in_oklab,var(--negative)_10%,transparent)] px-3 py-2 text-[13px] text-[var(--negative)]">{b!.lastError}</p>}
        <ErrorText error={run.error} />
        <Button variant="secondary" size="sm" className="mt-3" disabled={run.isPending} onClick={() => run.mutate(undefined)}>
          {run.isPending ? 'Backing up…' : 'Back up now'}
        </Button>
        {(b?.backups.length ?? 0) > 0 && (
          <ul className="mt-3 divide-y divide-[var(--line)] border-t border-[var(--line)]">
            {b!.backups.slice(0, 5).map((x) => (
              <li key={x.file}>
                <a href={`/api/backups/${x.file}`} className="row -mx-2 flex items-center gap-2 rounded-lg px-2 py-2 text-[13px]">
                  <DownloadSimple size={16} className="ink-2" />
                  <span className="min-w-0 flex-1 truncate">{dateTime(x.createdAt)}</span>
                  <span className="ink-3 tabular">{(x.sizeBytes / 1024 / 1024).toFixed(1)} MB</span>
                </a>
              </li>
            ))}
          </ul>
        )}
        <p className="ink-3 mt-3 text-[12.5px]">Each backup is one zip with the database and saved statements, plus restore steps inside. They're kept on the server at {b?.dir ?? 'the backups folder'}; copy them elsewhere too for safety.</p>
      </Card>
    </Section>
  )
}

/** Which Pocket ID (OIDC) account is linked, with a way to unlink it. Hidden when OIDC isn't set up. */
function PocketIdCard() {
  const opts = useQuery({ queryKey: ['auth-options'], queryFn: () => api<{ oidc?: { name: string } }>('/auth/options') })
  const link = useQuery({ queryKey: ['oidc-link'], queryFn: () => api<{ linked: boolean; email?: string; linkedAt?: string }>('/oidc-link'), enabled: !!opts.data?.oidc })
  const unlink = useMoneyMutation(() => api('/oidc-link', { method: 'DELETE' }))
  const name = opts.data?.oidc?.name
  if (!name) return null
  return (
    <Section title={name}>
      <Card className="flex items-center gap-3 p-4">
        <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)]">
          <IdentificationCard size={20} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-medium">{link.data?.linked ? 'Linked' : 'Not linked yet'}</p>
          <p className="ink-2 truncate text-[13px]">
            {link.data?.linked ? `${link.data.email ?? 'account'}${link.data.linkedAt ? ` · since ${dateTime(link.data.linkedAt)}` : ''}` : `You'll be asked to link it the first time you sign in with ${name}.`}
          </p>
        </div>
        {link.data?.linked && (
          <Button variant="secondary" size="sm" onClick={() => confirm(`Unlink this ${name} account? Signing in with ${name} will ask to link again.`) && unlink.mutate(undefined)}>
            Unlink
          </Button>
        )}
      </Card>
    </Section>
  )
}

function AppVersion() {
  const h = useQuery({ queryKey: ['health'], queryFn: () => api<{ version?: string }>('/health'), staleTime: Infinity })
  return <p className="ink-3 mt-8 text-center text-[12px]">Hisaab {h.data?.version ?? ''}</p>
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
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)]">
            <DeviceMobile size={20} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="font-medium">Install Hisaab</p>
            <p className="ink-2 text-[13px]">Opens from your home screen, full screen, and shows your last data when offline.</p>
          </div>
          {i.canPrompt && (
            <Button size="sm" onClick={() => i.prompt()}>
              Install
            </Button>
          )}
        </div>
        {!i.canPrompt && ios && (
          <ol className="ink-2 mt-3 space-y-1.5 border-t border-[var(--line)] pt-3 text-[14px]">
            <li className="flex items-center gap-2">
              1. Tap <Export size={18} className="text-[var(--accent)]" aria-label="Share" /> in Safari's toolbar
            </li>
            <li className="flex items-center gap-2">
              2. Choose <PlusSquare size={18} aria-hidden /> <b className="text-[var(--ink)]">Add to Home Screen</b>
            </li>
          </ol>
        )}
      </Card>
    </Section>
  )
}
