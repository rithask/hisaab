import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CaretRight, ChatText, CloudArrowUp, Database, DeviceMobile, Export, PlusSquare, DownloadSimple, Envelope, GraduationCap, IdentificationCard, Key, LockSimple, SignOut, SlidersHorizontal, ToggleRight, X, type Icon as PhIcon } from '@phosphor-icons/react'
import { useEffect, useState, type ReactNode } from 'react'
import { Link, Navigate, useParams } from 'react-router-dom'
import { api, type BackupStatus, type Feature, type Learned, type MailImport, type TokenStatus } from '../api'
import { DatePicker } from '../components/DatePicker'
import { CopyButton } from '../components/SmsPasteSheet'
import { isIos, useInstall } from '../pwa'
import { Badge, Button, Card, Chip, cx, ErrorText, Field, Input, List, Meter, Notice, PageHeader, Section, Segmented, Toggle, Page } from '../components/ui'
import { count, dateTime, shortDate, ymd } from '../format'
import { useMoneyMutation, useSettings, useSignOut } from '../hooks'

type SectionKey = 'general' | 'features' | 'mail' | 'teaching' | 'security' | 'data'

const SECTIONS: { key: SectionKey; label: string; icon: PhIcon; hint: string }[] = [
  { key: 'general', label: 'General', icon: SlidersHorizontal, hint: 'Your name, Home, install the app' },
  { key: 'features', label: 'Features', icon: ToggleRight, hint: 'Turn optional parts of the app on or off' },
  { key: 'mail', label: 'Mail & SMS', icon: Envelope, hint: 'Alerts mailbox, forwarding bank SMS' },
  { key: 'teaching', label: 'Teaching', icon: GraduationCap, hint: 'What it learned from your corrections' },
  { key: 'security', label: 'Security', icon: LockSimple, hint: 'Sign-in, signing out' },
  { key: 'data', label: 'Import & backups', icon: Database, hint: 'Older emails, nightly backups' },
]

/**
 * Settings is a menu of sections. On phones the menu and each section are separate pages
 * (/settings, /settings/mail, …); on wide screens the menu stays on the left and the section shows beside it.
 */
export default function Settings() {
  const { section } = useParams()
  const current = SECTIONS.find((x) => x.key === section)
  if (section && !current) return <Navigate to="/settings" replace />
  const shown = current ?? SECTIONS[0]
  return (
    <Page key={section ?? ''}>
      <div className="lg:grid lg:grid-cols-[300px_minmax(0,1fr)] lg:items-start lg:gap-8">
        <div className={cx(section && 'hidden lg:block')}>
          <PageHeader back title="Settings" />
          <SectionMenu active={shown.key} />
          <AppVersion />
        </div>
        <div className={cx(!section && 'hidden lg:block', '[&>section:first-child]:mt-0')}>
          <PageHeader back title={shown.label} />
          {shown.key === 'general' && <GeneralSection />}
          {shown.key === 'features' && <FeaturesCard />}
          {shown.key === 'mail' && <MailSection />}
          {shown.key === 'teaching' && <LearnedCard />}
          {shown.key === 'security' && <SecuritySection />}
          {shown.key === 'data' && <DataSection />}
        </div>
      </div>
    </Page>
  )
}

/** The list of sections, with a badge where something needs attention (mailbox not set up, backups failing). */
function SectionMenu({ active }: { active: SectionKey }) {
  const s = useSettings()
  const backupsOn = s.data?.features?.backups !== false
  const backups = useQuery({ queryKey: ['backups'], queryFn: () => api<BackupStatus>('/backups'), enabled: !!s.data && backupsOn })
  const b = backups.data
  const badge: Partial<Record<SectionKey, ReactNode>> = {
    mail: s.data && !s.data.mail.configured ? <Badge tone="amber">not set up</Badge> : null,
    data: b && backupFailing(b) ? <Badge tone="red">failing</Badge> : null,
  }
  return (
    <List>
      {SECTIONS.map(({ key, icon: Icon, label, hint }) => (
        <Link key={key} to={`/settings/${key}`} aria-current={key === active ? 'page' : undefined} className="row flex items-center gap-3.5 px-4 py-3.5 lg:aria-[current=page]:bg-[var(--surface-2)]">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)] text-[var(--ink)]">
            <Icon size={20} />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-[15px] font-medium">{label}</span>
            <span className="ink-2 block truncate text-[12.5px]">{hint}</span>
          </span>
          {badge[key]}
          <CaretRight size={16} className="ink-3 shrink-0" />
        </Link>
      ))}
    </List>
  )
}

function GeneralSection() {
  const s = useSettings()
  const [owner, setOwner] = useState('')
  const [msg, setMsg] = useState<string | null>(null)
  useEffect(() => setOwner(s.data?.ownerName ?? ''), [s.data?.ownerName])
  const homeOpt = useMoneyMutation((v: boolean) => api('/settings', { method: 'PUT', body: { showInvestmentsOnHome: v } }))
  const save = useMoneyMutation(() => api('/settings', { method: 'PUT', body: { ownerName: owner } }), () => setMsg('Saved.'))
  return (
    <>
      <Card className="space-y-4 p-5">
        <Field label="Your name, as banks show it" hint="Used to spot transfers to yourself, e.g. UPI to ARJUN RAVI K">
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
          <p className="ink-2 mt-1 text-[13px]">When off, Home's net worth leaves out stocks and funds. They're still on the Invest tab.</p>
        </Card>
      </Section>

      <InstallCard />
    </>
  )
}

function MailSection() {
  const mail = useSettings().data?.mail
  return (
    <>
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
    </>
  )
}

function SecuritySection() {
  const signOut = useSignOut()
  return (
    <>
      <PocketIdCard />
      <Section title="This device">
        <button className="pressable surface flex h-12 w-full items-center justify-center gap-2 rounded-[20px] text-[15px] font-semibold text-[var(--negative)]" onClick={signOut}>
          <SignOut size={17} /> Sign out
        </button>
      </Section>
      <p className="ink-3 mt-3 px-1 text-[12.5px]">The SMS forwarding token is managed in Mail &amp; SMS.</p>
    </>
  )
}

function DataSection() {
  const s = useSettings()
  return (
    <>
      <ImportOlderMail configured={!!s.data?.mail.configured} />
      {s.data?.features?.backups !== false && <BackupsCard />}
    </>
  )
}

const backupFailing = (b: BackupStatus) => !!b.lastError && (!b.lastOk || (b.lastErrorAt ?? '') > b.lastOk)

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
    </Section>
  )
}

/** Everything the SMS parser has picked up, with a way to make it forget a mistake. */
function LearnedCard() {
  const learned = useQuery({ queryKey: ['sms-learned'], queryFn: () => api<Learned>('/sms/learned') })
  const forgetFormat = useMoneyMutation((id: number) => api(`/sms/formats/${id}`, { method: 'DELETE' }))
  const resumeFormat = useMoneyMutation((id: number) => api(`/sms/formats/${id}/resume`, { method: 'POST' }))
  const forgetPayee = useMoneyMutation((id: number) => api(`/sms/payees/${id}`, { method: 'DELETE' }))
  const l = learned.data
  const empty = !l || (l.formats.length === 0 && l.payees.length === 0)
  return (
    <Card className="p-5">
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
          Nothing yet. It learns when you correct a message in the Inbox, mark one as not a transaction, rename a payee on a transaction, or pick the account for a message it couldn't place.
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
                        {f.auto ? 'Learned automatically · ' : ''}
                        {f.channel === 'EMAIL' ? 'Email · ' : ''}
                        {f.kind === 'SKIP' ? 'not a transaction' : `${f.institution} · ${f.direction === 'DEBIT' ? 'money out' : 'money in'}`} · used {f.hits} time{f.hits === 1 ? '' : 's'}
                        {f.lastUsedAt ? ` · last ${dateTime(f.lastUsedAt)}` : ''}
                        {f.corrections > 0 ? ` · corrected ${f.corrections === 1 ? 'once' : `${f.corrections} times`}` : ''}
                      </p>
                      {f.paused && (
                        <p className="mt-1.5 flex items-center gap-2 text-[11px] text-amber-700 dark:text-amber-300">
                          Paused: it kept being corrected, so its messages wait for review.
                          <button className="link font-semibold" onClick={() => resumeFormat.mutate(f.id)}>
                            Resume
                          </button>
                        </p>
                      )}
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
function FeaturesCard() {
  const features = useSettings().data?.features
  const set = useMoneyMutation((change: Partial<Record<Feature, boolean>>) => api('/settings', { method: 'PUT', body: { features: change } }))
  return (
    <>
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
    </>
  )
}

function BackupsCard() {
  const q = useQuery({ queryKey: ['backups'], queryFn: () => api<BackupStatus>('/backups') })
  const run = useMoneyMutation(() => api('/backups', { method: 'POST' }))
  const b = q.data
  const failing = !!b && backupFailing(b)
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

/** The same day n months ago (clamped to the month's length), as yyyy-mm-dd. */
const monthsAgo = (n: number) => {
  const [y, m, d] = ymd().split('-').map(Number)
  const last = new Date(Date.UTC(y, m - 1 - n + 1, 0)).getUTCDate()
  return new Date(Date.UTC(y, m - 1 - n, Math.min(d, last))).toISOString().slice(0, 10)
}

/**
 * Pull in older alerts from the mailbox. The import runs on the server, so its progress comes from there: leaving
 * the page (or reloading) and coming back shows where it really is, and the button stays busy until it's done.
 */
function ImportOlderMail({ configured }: { configured: boolean }) {
  const qc = useQueryClient()
  const [since, setSince] = useState('')
  const status = useQuery({
    queryKey: ['mail-import'],
    queryFn: () => api<MailImport>('/mail/import'),
    // check every second while it runs; otherwise only when the page opens
    refetchInterval: (q) => (q.state.data?.state === 'CONNECTING' || q.state.data?.state === 'RUNNING' ? 1000 : false),
  })
  const s = status.data
  const running = s?.state === 'CONNECTING' || s?.state === 'RUNNING'
  const start = useMoneyMutation((d: string) => api<MailImport>('/mail/import', { body: { since: d } }), (r) => qc.setQueryData(['mail-import'], r))
  const cancel = useMoneyMutation(() => api<MailImport>('/mail/import/cancel', { method: 'POST' }), (r) => qc.setQueryData(['mail-import'], r))

  // When an import finishes, what it added should show everywhere (inbox, transactions, totals).
  const finishedAt = s?.finishedAt
  useEffect(() => {
    if (finishedAt) qc.invalidateQueries({ predicate: (q) => q.queryKey[0] !== 'me' && q.queryKey[0] !== 'mail-import' })
  }, [finishedAt, qc])

  const pct = s?.total ? (s.done / s.total) * 100 : 0
  return (
    <Section title="Import older emails">
      <Card className="space-y-4 p-5">
        <p className="ink-2 text-sm">Only mail that arrives after setup is read automatically. Pick a start date to pull in older alerts. Balances only count transactions after each account's “as of” date.</p>
        <div>
          <p className="ink-2 mb-1.5 px-1 text-[13px] font-medium">Since</p>
          <DatePicker label="Import emails since" value={running ? (s?.since ?? since) : since} max={ymd()} disabled={running} onChange={setSince} />
          {!running && (
            <div className="mt-2.5 flex flex-wrap gap-2">
              {[
                [1, '1 month ago'],
                [3, '3 months ago'],
                [6, '6 months ago'],
                [12, '1 year ago'],
              ].map(([n, text]) => {
                const d = monthsAgo(n as number)
                return (
                  <Chip key={d} active={since === d} onClick={() => setSince(d)}>
                    {text}
                  </Chip>
                )
              })}
            </div>
          )}
        </div>

        {running && s && (
          <div role="status" aria-live="polite">
            <Meter pct={pct} />
            <div className="mt-1.5 flex justify-between gap-3 text-[13px]">
              <span className="ink-2">
                {s.state === 'CONNECTING' ? 'Signing in and finding emails…' : `${count(s.done)} of ${count(s.total ?? 0)} emails`}
              </span>
              <span className="ink-2 tabular">{s.state === 'RUNNING' ? `${count(s.added)} new · ${Math.floor(pct)}%` : ''}</span>
            </div>
          </div>
        )}
        {!running && s && s.finishedAt && (
          <p className={'text-[13px] ' + (s.state === 'FAILED' ? 'text-[var(--negative)]' : 'ink-2')}>
            {s.state === 'FAILED'
              ? `The import since ${shortDate(s.since!)} stopped: ${s.error}`
              : `${s.state === 'CANCELLED' ? 'Stopped' : 'Finished'} ${dateTime(s.finishedAt)}: ${count(s.added)} new of ${count(s.done)} emails since ${shortDate(s.since!)}${s.state === 'CANCELLED' ? ` (${count((s.total ?? 0) - s.done)} not looked at)` : ''}.`}
          </p>
        )}
        <ErrorText error={start.error ?? cancel.error} />

        <div className="flex gap-2">
          <Button variant="secondary" disabled={!since || running || start.isPending || !configured} onClick={() => start.mutate(since)}>
            {running ? 'Importing…' : 'Import'}
          </Button>
          {running && (
            <Button variant="secondary" disabled={cancel.isPending} onClick={() => cancel.mutate(undefined)}>
              Stop
            </Button>
          )}
        </div>
      </Card>
    </Section>
  )
}
