import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowCounterClockwise, CaretRight, ChatText, CloudArrowUp, Database, DeviceMobile, Export, PlusSquare, DownloadSimple, Envelope, GraduationCap, IdentificationCard, LockSimple, SignOut, SlidersHorizontal, Tag, ToggleRight, Tray, X, type Icon as PhIcon } from '@phosphor-icons/react'
import { useEffect, useState, type ReactNode } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { api, type BackupStatus, type Feature, type Learned, type MailImport, type ResetPart, type ResetPreview } from '../api'
import { TokenList } from '../components/ApiTokens'
import { DatePicker } from '../components/DatePicker'
import { CopyButton } from '../components/SmsPasteSheet'
import { clearSavedData, isIos, useInstall } from '../pwa'
import { Badge, Button, Card, Chip, Confirm, cx, ErrorText, Field, Input, List, Meter, Notice, PageHeader, Section, Segmented, Sheet, Toggle, Page, IconBadge, IconButton, FieldLabel } from '../components/ui'
import { count, dateTime, shortDate, ymd, fileSize, plural, joinList } from '../format'
import { useMoneyMutation, useSettings, useSignOut } from '../hooks'

type SectionKey = 'general' | 'features' | 'mail' | 'teaching' | 'security' | 'data'

const SECTIONS: { key: SectionKey; label: string; icon: PhIcon; hint: string }[] = [
  { key: 'general', label: 'General', icon: SlidersHorizontal, hint: 'Your name, Home, install the app' },
  { key: 'features', label: 'Features', icon: ToggleRight, hint: 'Turn optional parts of the app on or off' },
  { key: 'mail', label: 'Mail & SMS', icon: Envelope, hint: 'Alerts mailbox, forwarding bank SMS' },
  { key: 'teaching', label: 'What it learned', icon: GraduationCap, hint: 'From your corrections: wordings, payee names' },
  { key: 'security', label: 'Security', icon: LockSimple, hint: 'Sign-in, API tokens, signing out' },
  { key: 'data', label: 'Import & backups', icon: Database, hint: 'Older emails, nightly backups, reset' },
]

/** Pages of their own that belong with the settings, listed after the sections. */
const PAGES: { to: string; label: string; icon: PhIcon; hint: string }[] = [
  { to: '/categories', label: 'Categories & rules', icon: Tag, hint: 'Your categories and auto-filing' },
  { to: '/inbox', label: 'Messages', icon: Tray, hint: 'Bank emails and SMS, paste one' },
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
    mail: s.data && !s.data.mail.configured ? <Badge tone="attention">not set up</Badge> : null,
    data: b && backupFailing(b) ? <Badge tone="negative">failing</Badge> : null,
  }
  return (
    <List>
      {SECTIONS.map(({ key, icon: Icon, label, hint }) => (
        <Link key={key} to={`/settings/${key}`} aria-current={key === active ? 'page' : undefined} className="row flex items-center gap-3.5 px-4 py-3.5 lg:aria-[current=page]:bg-surface-2">
          <IconBadge icon={Icon} />
          <span className="min-w-0 flex-1">
            <span className="block text-body font-medium">{label}</span>
            <span className="ink-2 block truncate text-caption">{hint}</span>
          </span>
          {badge[key]}
          <CaretRight size={16} className="ink-3 shrink-0" />
        </Link>
      ))}
      {PAGES.map(({ to, icon: Icon, label, hint }) => (
        <Link key={to} to={to} className="row flex items-center gap-3.5 px-4 py-3.5">
          <IconBadge icon={Icon} />
          <span className="min-w-0 flex-1">
            <span className="block text-body font-medium">{label}</span>
            <span className="ink-2 block truncate text-caption">{hint}</span>
          </span>
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

function MailSection() {
  const mail = useSettings().data?.mail
  return (
    <>
      <Section title="Mailbox">
        <Card className="p-5">
          <div className="flex items-center gap-3">
            <IconBadge icon={Envelope} />
            <div className="flex-1">
              <p className="font-semibold">Alerts mailbox</p>
              <p className="ink-2 text-caption">{mail?.lastSync ? `Last checked ${dateTime(mail.lastSync)}` : 'Never checked'}</p>
            </div>
            {mail?.configured ? <Badge tone="positive">connected</Badge> : <Badge tone="attention">not set up</Badge>}
          </div>
          {mail?.lastError && <p className="mt-3 text-label text-negative">{mail.lastError}</p>}
          {!mail?.configured && (
            <p className="ink-2 mt-3 text-label leading-relaxed">
              Start the server with <code className="rounded bg-surface-2 px-1">MAIL_HOST</code>, <code className="rounded bg-surface-2 px-1">MAIL_USERNAME</code> and{' '}
              <code className="rounded bg-surface-2 px-1">MAIL_PASSWORD</code>. Gmail: imap.gmail.com · iCloud: imap.mail.me.com, with an app-specific password.
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
      <Section title="API tokens">
        <p className="ink-2 mb-2 px-1 text-small">Apps and shortcuts that can use Hisaab. Each token does one job, within the limits you set.</p>
        <TokenList />
        <p className="ink-3 mt-2 px-1 text-caption leading-relaxed">Tokens are stored hashed and shown once. Repeated wrong tokens are locked out for 15 minutes, and every use is logged.</p>
      </Section>
      <Section title="This device">
        <button className="pressable surface flex h-12 w-full items-center justify-center gap-2 rounded-card text-body font-semibold text-negative" onClick={signOut}>
          <SignOut size={17} /> Sign out
        </button>
      </Section>
    </>
  )
}

function DataSection() {
  const s = useSettings()
  return (
    <>
      <ImportOlderMail configured={!!s.data?.mail.configured} />
      {s.data?.features?.backups !== false && <BackupsCard />}
      <ResetCard />
    </>
  )
}

const backupFailing = (b: BackupStatus) => !!b.lastError && (!b.lastOk || (b.lastErrorAt ?? '') > b.lastOk)

/** Send SMS tokens + setup guide for forwarding bank SMS from the phone. */
function SmsForwarding() {
  const [guide, setGuide] = useState<'ios' | 'android' | 'curl'>('ios')
  const url = `${window.location.origin}/api/v1/sms`

  return (
    <Section title="SMS forwarding">
      <Card className="mb-3 p-4">
        <div className="flex items-center gap-3">
          <IconBadge icon={ChatText} />
          <div className="min-w-0 flex-1">
            <p className="font-semibold">Bank SMS</p>
            <p className="ink-2 text-caption">For alerts that only come by text message. Each phone can have its own token.</p>
          </div>
        </div>
        <div className="mt-3 rounded-card bg-surface-2 p-3">
          <p className="ink-2 text-caption font-medium">Endpoint</p>
          <div className="mt-1 flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate font-mono text-caption">POST {url}</code>
            <CopyButton value={url} />
          </div>
        </div>
      </Card>
      <TokenList jobs={['SEND_SMS']} newJob="SEND_SMS" newLabel="New SMS token" />
      <p className="ink-3 mt-2 px-1 text-caption leading-relaxed">An SMS token can only send SMS; it can't read or change anything else. All your tokens are in Security.</p>

      <Card className="mt-3 p-5">
        <p className="mb-3 font-semibold">Set it up on your phone</p>
        <Segmented3 value={guide} onChange={setGuide} />
        <div className="mt-4 text-label leading-relaxed">
          {guide === 'ios' && (
            <ol className="ink-2 list-decimal space-y-2 pl-5 marker:font-semibold marker:text-ink">
              <li>
                Open <b className="text-ink">Shortcuts</b> → <b className="text-ink">Automation</b> → <b className="text-ink">New Automation</b> → <b className="text-ink">Message</b>.
              </li>
              <li>
                Set <b className="text-ink">Message Contains</b> to <code className="rounded bg-surface-2 px-1">debited</code> (make a second one for <code className="rounded bg-surface-2 px-1">credited</code>, or one per bank sender). Choose <b className="text-ink">Run Immediately</b>.
              </li>
              <li>
                Add <b className="text-ink">Get Contents of URL</b>: URL above, Method <b className="text-ink">POST</b>, Header <code className="rounded bg-surface-2 px-1">Authorization</code> = <code className="break-words rounded bg-surface-2 px-1">Bearer &lt;token&gt;</code>.
              </li>
              <li>
                Request Body <b className="text-ink">JSON</b>: <code className="rounded bg-surface-2 px-1">text</code> = Shortcut Input › Content, <code className="rounded bg-surface-2 px-1">sender</code> = Shortcut Input › Sender.
              </li>
              <li>
                Optional: <b className="text-ink">Get Dictionary Value</b> <code className="rounded bg-surface-2 px-1">message</code> → <b className="text-ink">Show Notification</b>, to see what was recorded.
              </li>
            </ol>
          )}
          {guide === 'android' && (
            <ol className="ink-2 list-decimal space-y-2 pl-5 marker:font-semibold marker:text-ink">
              <li>
                Install an SMS-to-webhook app (e.g. <b className="text-ink">SMS Forwarder</b>, MacroDroid or Tasker).
              </li>
              <li>Add a rule for your bank senders (contains HDFCBK, AXISBK, SBI, CANBNK…).</li>
              <li>
                Webhook: POST to the URL above with header <code className="break-all rounded bg-surface-2 px-1">Authorization: Bearer &lt;token&gt;</code> and JSON body{' '}
                <code className="rounded bg-surface-2 px-1">{'{"text": "%body%", "sender": "%from%"}'}</code>.
              </li>
            </ol>
          )}
          {guide === 'curl' && (
            <pre className="overflow-x-auto rounded-box bg-surface-2 p-3 font-mono text-micro leading-relaxed">{`curl -X POST ${url} \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"sender":"AX-HDFCBK","text":"Sent Rs.500.00 From HDFC Bank A/C *2286 To RAHUL On 05/11/26 Ref 531234567890"}'`}</pre>
          )}
        </div>
        <p className="ink-3 mt-4 text-caption leading-relaxed">
          Payments that also arrive by email are recorded once. You can also paste an SMS by hand from <b>Settings → Messages → Paste SMS</b>. Your phone needs to reach this server (e.g. over Tailscale or your public URL).
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
        <IconBadge icon={GraduationCap} />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">What it has learned</p>
          <p className="ink-2 text-caption">
            {l ? `${l.formats.length} message wording${l.formats.length === 1 ? '' : 's'} · ${l.payees.length} payee name${l.payees.length === 1 ? '' : 's'} · ${l.senders} sender${l.senders === 1 ? '' : 's'}` : ' '}
          </p>
        </div>
      </div>
      {empty ? (
        <p className="ink-2 mt-4 text-label leading-relaxed">
          Nothing yet. It learns when you correct a message (Settings → Messages), mark one as not a transaction, rename a payee on a transaction, or pick the account for a message it couldn't place.
        </p>
      ) : (
        <div className="mt-4 space-y-4">
          {l!.formats.length > 0 && (
            <div>
              <FieldLabel>How messages are worded</FieldLabel>
              <ul className="space-y-2">
                {l!.formats.map((f) => (
                  <li key={f.id} className="flex items-start gap-2 rounded-box bg-surface-2 p-3">
                    <Confirm ask="Forget this wording?" yes="Forget" busy={forgetFormat.isPending} onYes={() => forgetFormat.mutate(f.id)}>
                    {(ask) => (<>
                    <div className="min-w-0 flex-1">
                      <p className="break-words font-mono text-micro leading-relaxed">{f.skeleton}</p>
                      <p className="ink-3 mt-1 text-micro">
                        {f.auto ? 'Learned automatically · ' : ''}
                        {f.channel === 'EMAIL' ? 'Email · ' : ''}
                        {f.kind === 'SKIP' ? 'not a transaction' : `${f.institution} · ${f.direction === 'DEBIT' ? 'money out' : 'money in'}`} · used {f.hits} time{f.hits === 1 ? '' : 's'}
                        {f.lastUsedAt ? ` · last ${dateTime(f.lastUsedAt)}` : ''}
                        {f.corrections > 0 ? ` · corrected ${f.corrections === 1 ? 'once' : `${f.corrections} times`}` : ''}
                      </p>
                      {f.paused && (
                        <p className="mt-1.5 flex items-center gap-2 text-micro text-attention">
                          Paused: it kept being corrected, so its messages wait for review.
                          <button className="link font-semibold" onClick={() => resumeFormat.mutate(f.id)}>
                            Resume
                          </button>
                        </p>
                      )}
                    </div>
                    <IconButton icon={X} label="Forget this wording" size="xs" variant="plain" onClick={ask} />
                    </>)}
                    </Confirm>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {l!.payees.length > 0 && (
            <div>
              <FieldLabel>Payee names</FieldLabel>
              <ul className="space-y-2">
                {l!.payees.map((p) => (
                  <li key={p.id} className="flex items-center gap-2 rounded-box bg-surface-2 px-3 py-2.5 text-small">
                    <Confirm ask="Forget this name?" yes="Forget" busy={forgetPayee.isPending} onYes={() => forgetPayee.mutate(p.id)}>
                      {(ask) => (
                        <>
                          <span className="ink-2 min-w-0 flex-1 truncate font-mono text-micro uppercase">{p.rawKey}</span>
                          <span className="ink-3">→</span>
                          <span className="min-w-0 flex-1 truncate font-medium">{p.displayName}</span>
                          <IconButton icon={X} label="Forget this name" size="xs" variant="plain" onClick={ask} />
                        </>
                      )}
                    </Confirm>
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

const FEATURES: { key: Feature; name: string; hint: string; off?: boolean }[] = [
  { key: 'backups', name: 'Automatic backups', hint: 'Nightly copy of everything, kept on the server' },
  { key: 'budgets', name: 'Budgets', hint: 'Monthly limits per category' },
  { key: 'rewards', name: 'Rewards', hint: 'Cashback and points on your credit cards' },
  { key: 'data_api', name: 'Data API', hint: 'Let other apps read or add transactions with a token (Security → API tokens)', off: true },
]

/** Switch optional features off: they disappear from the app and their background jobs stop. */
function FeaturesCard() {
  const features = useSettings().data?.features
  const set = useMoneyMutation((change: Partial<Record<Feature, boolean>>) => api('/settings', { method: 'PUT', body: { features: change } }))
  return (
    <>
      <Card className="divide-y divide-line p-0">
        {FEATURES.map((f) => (
          <div key={f.key} className="px-4 py-2.5">
            <Toggle
              checked={features?.[f.key] ?? !f.off}
              onChange={(v) => set.mutate({ [f.key]: v })}
              label={
                <span>
                  <span className="block text-body">{f.name}</span>
                  <span className="ink-2 block text-caption">{f.hint}</span>
                </span>
              }
            />
          </div>
        ))}
      </Card>
      <p className="ink-3 mt-2 px-1 text-caption">Turning a feature off hides it everywhere and stops any background work for it. Nothing is deleted.</p>
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
          <IconBadge icon={CloudArrowUp} />
          <div className="min-w-0 flex-1">
            <p className="font-medium">Every night at 3:30 am</p>
            <p className="ink-2 truncate text-small">{b?.lastOk ? `Last backup ${dateTime(b.lastOk)}` : 'No backup yet'} · keeps {b?.keep ?? 14}</p>
          </div>
          {failing ? <Badge tone="negative">failing</Badge> : b?.lastOk ? <Badge tone="positive">ok</Badge> : null}
        </div>
        {failing && <p className="mt-3 rounded-field bg-[color-mix(in_oklab,var(--negative)_10%,transparent)] px-3 py-2 text-small text-negative">{b!.lastError}</p>}
        <ErrorText error={run.error} />
        <Button variant="secondary" size="sm" className="mt-3" disabled={run.isPending} onClick={() => run.mutate(undefined)}>
          {run.isPending ? 'Backing up…' : 'Back up now'}
        </Button>
        {(b?.backups.length ?? 0) > 0 && (
          <ul className="mt-3 divide-y divide-line border-t border-line">
            {b!.backups.slice(0, 5).map((x) => (
              <li key={x.file}>
                <a href={`/api/backups/${x.file}`} className="row -mx-2 flex items-center gap-2 rounded-lg px-2 py-2 text-small">
                  <DownloadSimple size={16} className="ink-2" />
                  <span className="min-w-0 flex-1 truncate">{dateTime(x.createdAt)}</span>
                  <span className="ink-3 tabular">{fileSize(x.sizeBytes)}</span>
                </a>
              </li>
            ))}
          </ul>
        )}
        <p className="ink-3 mt-3 text-caption">Each backup is one zip with the database and saved statements, plus restore steps inside. They're kept on the server at {b?.dir ?? 'the backups folder'}; copy them elsewhere too for safety.</p>
      </Card>
    </Section>
  )
}


/** Each choice in the reset sheet: what it's called and what it would remove right now. */
const RESET_PARTS: { key: ResetPart; label: string; detail: (p: ResetPreview) => string }[] = [
  {
    key: 'DATA',
    label: 'Money data',
    detail: (p) =>
      joinList([
        p.accounts > 0 && plural(p.accounts, 'account'),
        p.transactions > 0 && plural(p.transactions, 'transaction'),
        p.people > 0 && plural(p.people, 'person', 'people'),
        p.statements > 0 && plural(p.statements, 'statement'),
        p.holdings > 0 && plural(p.holdings, 'investment'),
        p.messages > 0 && plural(p.messages, 'saved message'),
      ]) || 'Nothing saved yet',
  },
  { key: 'LEARNED', label: 'What the app learned', detail: (p) => (p.learned > 0 ? `${plural(p.learned, 'thing')} learned from your corrections: rules, payee names, SMS formats` : 'Nothing learned yet') },
  { key: 'CATEGORIES', label: 'Categories', detail: (p) => `${plural(p.categories, 'category', 'categories')} now; the built-in ones come back` },
  { key: 'SETTINGS', label: 'Settings', detail: (p) => (p.settings > 0 ? 'Feature switches, your name and Home options go back to the defaults' : 'Already at the defaults') },
  {
    key: 'CONNECTIONS',
    label: 'Connected apps',
    detail: (p) => joinList([p.tokens > 0 && plural(p.tokens, 'API token'), p.pocketIdLinked && 'the Pocket ID link']) || 'Nothing connected',
  },
]

/** Parts a choice can't go without (same rule as the server): the data takes what was learned, categories need the data. */
const needed = (chosen: Set<ResetPart>) => {
  const s = new Set(chosen)
  if (s.has('CATEGORIES')) s.add('DATA')
  if (s.has('DATA')) s.add('LEARNED')
  return s
}
const lockedBy = (key: ResetPart, chosen: Set<ResetPart>) =>
  key === 'DATA' && chosen.has('CATEGORIES') ? 'Goes with Categories' : key === 'LEARNED' && (chosen.has('DATA') || chosen.has('CATEGORIES')) ? 'Goes with Money data' : null

/** Settings → Import & backups → Reset: choose what to clear, read what it means, type "reset". */
function ResetCard() {
  const [open, setOpen] = useState(false)
  const [done, setDone] = useState<string | null>(null)
  return (
    <Section title="Reset">
      <Card className="p-4">
        <div className="flex items-center gap-3">
          <IconBadge icon={ArrowCounterClockwise} />
          <div className="min-w-0 flex-1">
            <p className="font-medium">Start again</p>
            <p className="ink-2 text-small">Clear your data, what the app learned, or your settings. You choose which.</p>
          </div>
        </div>
        {/* under the text, like Back up now, so the text keeps the full width */}
        <Button
          variant="danger"
          size="sm"
          className="mt-3"
          onClick={() => {
            setDone(null)
            setOpen(true)
          }}
        >
          Reset
        </Button>
        {done && (
          <div className="mt-3">
            <Notice>{done}</Notice>
          </div>
        )}
      </Card>
      <ResetSheet open={open} onClose={() => setOpen(false)} onDone={setDone} />
    </Section>
  )
}

function ResetSheet({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: (msg: string) => void }) {
  const nav = useNavigate()
  const [step, setStep] = useState<'choose' | 'confirm'>('choose')
  const [chosen, setChosen] = useState<Set<ResetPart>>(new Set())
  const [word, setWord] = useState('')
  const [withoutBackup, setWithoutBackup] = useState(false)
  const q = useQuery({ queryKey: ['reset'], queryFn: () => api<ResetPreview>('/reset'), enabled: open })
  const p = q.data
  const parts = needed(chosen)
  const run = useMoneyMutation(
    () => api<{ backup?: string }>('/reset', { body: { parts: [...parts], word, withoutBackup } }),
    async (r) => {
      await clearSavedData()
      close()
      onDone(r.backup ? `Reset done. A backup from just before is saved as ${r.backup}.` : 'Reset done.')
      if (parts.has('DATA')) nav('/')
    },
  )

  // start fresh each time it opens
  useEffect(() => {
    if (open) {
      setStep('choose')
      setChosen(new Set())
      setWord('')
      setWithoutBackup(false)
      run.reset()
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  function close() {
    if (!run.isPending) onClose()
  }
  const toggle = (k: ResetPart, on: boolean) =>
    setChosen((c) => {
      const n = new Set(c)
      if (on) n.add(k)
      else n.delete(k)
      return n
    })

  const shown = RESET_PARTS.filter((x) => parts.has(x.key))
  const backupOk = !!p?.backupsOn || withoutBackup
  const ready = word.trim().toLowerCase() === 'reset' && backupOk

  return (
    <Sheet open={open} onClose={close} title="Reset">
      {!p ? (
        <div className="py-6">{q.error ? <ErrorText error={q.error} /> : <p className="ink-2 text-label">Checking what's saved…</p>}</div>
      ) : step === 'choose' ? (
        <div className="flex flex-1 flex-col gap-4 pb-2">
          <p className="ink-2 text-label">Choose what to clear. Your sign-in, your backups and where mail reading got to are always kept.</p>
          <Card className="divide-y divide-line px-4">
            {RESET_PARTS.map((x) => {
              const lock = lockedBy(x.key, chosen)
              return (
                <div key={x.key} className={cx('py-3', lock && 'pointer-events-none opacity-60')}>
                  <Toggle
                    checked={parts.has(x.key)}
                    onChange={(v) => toggle(x.key, v)}
                    label={
                      <span className="block">
                        <span className="block font-medium">{x.label}</span>
                        <span className="ink-2 block text-small">{lock ?? x.detail(p)}</span>
                      </span>
                    }
                  />
                </div>
              )
            })}
          </Card>
          <Button className="mt-auto w-full" variant="danger" disabled={parts.size === 0} onClick={() => setStep('confirm')}>
            Continue
          </Button>
        </div>
      ) : (
        <div className="flex flex-1 flex-col gap-4 pb-2">
          <div className="anim-pop rounded-box bg-[color-mix(in_oklab,var(--negative)_13%,transparent)] px-4 py-3 text-label text-negative">
            <p className="font-semibold">This can't be undone.</p>
            <ul className="mt-1.5 space-y-1">
              {shown.map((x) => (
                <li key={x.key}>
                  <b>{x.label}:</b> {x.detail(p)}
                </li>
              ))}
            </ul>
          </div>
          {p.backupsOn ? (
            <p className="ink-2 text-small">A backup is taken first. If it fails, nothing is reset.</p>
          ) : (
            <Card className="px-4 py-3">
              <Toggle
                checked={withoutBackup}
                onChange={setWithoutBackup}
                label={
                  <span className="block">
                    <span className="block font-medium">Reset without a backup</span>
                    <span className="ink-2 block text-small">Backups are off, so there won't be a copy to go back to.</span>
                  </span>
                }
              />
            </Card>
          )}
          <Field label="Type “reset” to confirm">
            <Input value={word} onChange={(e) => setWord(e.target.value)} autoCapitalize="none" autoCorrect="off" autoComplete="off" spellCheck={false} enterKeyHint="done" />
          </Field>
          <ErrorText error={run.error} />
          <div className="mt-auto flex gap-2">
            <Button variant="secondary" className="flex-1" disabled={run.isPending} onClick={() => setStep('choose')}>
              Back
            </Button>
            <Button variant="danger" className="flex-1" disabled={!ready || run.isPending} onClick={() => run.mutate(undefined)}>
              {run.isPending ? (p.backupsOn ? 'Backing up…' : 'Resetting…') : 'Reset now'}
            </Button>
          </div>
        </div>
      )}
    </Sheet>
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
        <IconBadge icon={IdentificationCard} />
        <div className="min-w-0 flex-1">
          <p className="font-medium">{link.data?.linked ? 'Linked' : 'Not linked yet'}</p>
          <p className="ink-2 truncate text-small">
            {link.data?.linked ? `${link.data.email ?? 'account'}${link.data.linkedAt ? ` · since ${dateTime(link.data.linkedAt)}` : ''}` : `You'll be asked to link it the first time you sign in with ${name}.`}
          </p>
        </div>
        {link.data?.linked && (
          <Confirm className="mt-3" ask={`Unlink this ${name} account? Signing in with ${name} will ask to link again.`} yes="Unlink" busy={unlink.isPending} onYes={() => unlink.mutate(undefined)}>
            {(ask) => (
              <Button variant="secondary" size="sm" onClick={ask}>
                Unlink
              </Button>
            )}
          </Confirm>
        )}
      </Card>
    </Section>
  )
}

function AppVersion() {
  const h = useQuery({ queryKey: ['health'], queryFn: () => api<{ version?: string }>('/health'), staleTime: Infinity })
  return <p className="ink-3 mt-8 text-center text-caption">Hisaab {h.data?.version ?? ''}</p>
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
        <p className="ink-2 text-label">Only mail that arrives after setup is read automatically. Pick a start date to pull in older alerts. Balances only count transactions after each account's “as of” date.</p>
        <div>
          <p className="ink-2 mb-1.5 px-1 text-small font-medium">Since</p>
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
            <div className="mt-1.5 flex justify-between gap-3 text-small">
              <span className="ink-2">
                {s.state === 'CONNECTING' ? 'Signing in and finding emails…' : `${count(s.done)} of ${count(s.total ?? 0)} emails`}
              </span>
              <span className="ink-2 tabular">{s.state === 'RUNNING' ? `${count(s.added)} new · ${Math.floor(pct)}%` : ''}</span>
            </div>
          </div>
        )}
        {!running && s && s.finishedAt && (
          <p className={'text-small ' + (s.state === 'FAILED' ? 'text-negative' : 'ink-2')}>
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
