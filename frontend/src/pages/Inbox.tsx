import { useQuery } from '@tanstack/react-query'
import { ArrowsClockwise, ChatText, GraduationCap, EnvelopeOpen, EnvelopeSimple, ShieldCheck, UploadSimple, Warning } from '@phosphor-icons/react'
import { useEffect, useRef, useState } from 'react'
import { api, qs, type Alert, type Email, type EmailStatus, type TeachResult } from '../api'
import { Badge, Button, Card, Chip, cx, Empty, ErrorText, List, Notice, PageHeader, Section, Sheet, Page } from '../components/ui'
import { count, dateTime, money } from '../format'
import { useMoneyMutation } from '../hooks'
import SmsPasteSheet from '../components/SmsPasteSheet'
import TeachForm from '../components/TeachForm'

const tone: Record<EmailStatus, 'green' | 'amber' | 'red' | 'neutral' | 'blue'> = {
  PARSED: 'green',
  NEEDS_REVIEW: 'amber',
  ERROR: 'red',
  IGNORED: 'neutral',
  DUPLICATE: 'neutral',
  STATEMENT: 'blue',
}
const label = (s: EmailStatus) => s.replace('_', ' ').toLowerCase()

export default function Inbox() {
  const [filter, setFilter] = useState<EmailStatus | ''>('')
  const alerts = useQuery({ queryKey: ['alerts'], queryFn: () => api<Alert[]>('/alerts') })
  const review = useQuery({ queryKey: ['emails', 'review'], queryFn: async () => [...(await api<Email[]>('/emails?status=NEEDS_REVIEW')), ...(await api<Email[]>('/emails?status=ERROR'))] })
  const all = useQuery({ queryKey: ['emails', filter], queryFn: () => api<Email[]>('/emails' + qs({ status: filter })) })
  const [open, setOpen] = useState<number | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  // Text shared into the installed app (Android: Share → Money) lands here as ?text=…; read it as an SMS.
  const [shared] = useState(() => {
    const p = new URLSearchParams(window.location.search)
    const text = [p.get('text'), p.get('title')].find((x) => x && /\d/.test(x)) ?? p.get('text') ?? ''
    return text.trim()
  })
  const [pasting, setPasting] = useState(!!shared)
  useEffect(() => {
    if (shared) window.history.replaceState(null, '', '/inbox')
  }, [shared])

  const dismiss = useMoneyMutation((id: number) => api(`/alerts/${id}/dismiss`, { method: 'POST' }))
  const adjust = useMoneyMutation((id: number) => api(`/alerts/${id}/adjust`, { method: 'POST' }))
  const sync = useMoneyMutation(() => api<{ fetched: number; processed: number; error?: string }>('/mail/sync', { method: 'POST' }), (r) =>
    setMsg(r.error ? `Sync failed: ${r.error}` : `Checked mailbox: ${r.fetched} new message(s).`),
  )
  const retry = useMoneyMutation(() => api<{ parsed: number }>('/emails/reprocess-pending', { method: 'POST' }), (r) => setMsg(`Re-processed: ${r.parsed} now recorded.`))
  const upload = useMoneyMutation(
    (f: File) => {
      const form = new FormData()
      form.append('file', f)
      return api<{ added: boolean }>('/emails/upload', { form })
    },
    (r) => setMsg(r.added ? 'Email processed.' : 'Already seen this email.'),
  )

  return (
    <Page>
      <PageHeader back title="Inbox" subtitle="Bank emails, SMS and alerts" />
      <div className="grid grid-cols-3 gap-2">
        <Button variant="secondary" size="sm" className="h-11 whitespace-nowrap px-2 text-[13px]" onClick={() => sync.mutate(undefined)} disabled={sync.isPending}>
          <ArrowsClockwise size={16} /> {sync.isPending ? "Syncing…" : "Sync mail"}
        </Button>
        <Button variant="secondary" size="sm" className="h-11 whitespace-nowrap px-2 text-[13px]" onClick={() => setPasting(true)}>
          <ChatText size={16} /> Paste SMS
        </Button>
        <Button variant="secondary" size="sm" className="h-11 whitespace-nowrap px-2 text-[13px]" onClick={() => fileRef.current?.click()}>
          <UploadSimple size={16} /> .eml
        </Button>
        <input ref={fileRef} type="file" accept=".eml,message/rfc822" className="hidden" onChange={(e) => (e.target.files?.[0] && upload.mutate(e.target.files[0]), (e.target.value = ''))} />
      </div>
      {msg && (
        <div className="mt-3">
          <Notice tone="neutral">{msg}</Notice>
        </div>
      )}
      <ErrorText error={sync.error ?? upload.error ?? retry.error} />

      {(alerts.data ?? []).length > 0 && (
        <Section title="Balance mismatches">
          <div className="space-y-2">
            {alerts.data!.map((a) => (
              <Card key={a.id} className="anim-pop">
                <div className="flex gap-3">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-500/15 text-amber-600 dark:text-amber-400">
                    <Warning size={19} />
                  </span>
                  <div className="min-w-0 flex-1 text-sm">
                    <p className="font-semibold">{a.account?.name ?? 'Balance'} doesn't match</p>
                    <div className="mt-2 grid grid-cols-2 gap-2 rounded-2xl bg-[var(--surface-2)] p-3 text-xs">
                      <div>
                        <p className="ink-2">Bank says</p>
                        <p className="tabular text-[15px] font-semibold">{money(a.actual)}</p>
                      </div>
                      <div>
                        <p className="ink-2">App calculated</p>
                        <p className="tabular text-[15px] font-semibold">{money(a.expected)}</p>
                      </div>
                    </div>
                    <p className="ink-2 mt-2 text-xs">A transaction may be missing · {dateTime(a.createdAt)}</p>
                    <div className="mt-3 flex flex-wrap gap-2">
                      <Button size="sm" onClick={() => adjust.mutate(a.id)}>
                        Match bank
                      </Button>
                      <Button size="sm" variant="secondary" onClick={() => dismiss.mutate(a.id)}>
                        Dismiss
                      </Button>
                    </div>
                  </div>
                </div>
              </Card>
            ))}
          </div>
        </Section>
      )}

      {(review.data ?? []).length > 0 && (
        <Section title="Needs review" action={<button className="link text-[13px] font-semibold text-[var(--accent)]" onClick={() => retry.mutate(undefined)}>Retry all</button>}>
          <EmailList items={review.data!} onOpen={setOpen} />
        </Section>
      )}

      <Section title="All messages">
        <div className="no-scrollbar -mx-4 mb-3 flex gap-2 overflow-x-auto px-4">
          <Chip active={filter === ''} onClick={() => setFilter('')}>
            All
          </Chip>
          {(Object.keys(tone) as EmailStatus[]).map((s) => (
            <Chip key={s} active={filter === s} onClick={() => setFilter(s)}>
              {label(s)}
            </Chip>
          ))}
        </div>
        {all.data?.length === 0 ? (
          <Empty icon={EnvelopeSimple} title="No emails here">
            Forward bank alerts to your tracking mailbox, set up SMS forwarding in Settings, or paste an SMS.
          </Empty>
        ) : (
          <EmailList items={all.data ?? []} onOpen={setOpen} />
        )}
      </Section>

      <SmsPasteSheet open={pasting} onClose={() => setPasting(false)} initialText={shared} />
      <Sheet open={open != null} onClose={() => setOpen(null)} title="Message">
        {open != null && <EmailView id={open} onDone={() => setOpen(null)} />}
      </Sheet>
    </Page>
  )
}

function EmailList({ items, onOpen }: { items: Email[]; onOpen: (id: number) => void }) {
  return (
    <List>
      {items.map((e) => (
        <button key={e.id} onClick={() => onOpen(e.id)} className="flex w-full items-start gap-3 px-4 py-3 text-left row">
          <span className={cx('mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-[12px] bg-[var(--surface-2)]', e.status === 'PARSED' ? 'text-[var(--accent)]' : 'ink-2')}>
            {e.channel === 'SMS' ? <ChatText size={17} /> : <EnvelopeOpen size={17} />}
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex items-center justify-between gap-2">
              <span className="truncate text-[14.5px] font-semibold">{e.subject || '(no subject)'}</span>
              <Badge tone={tone[e.status]}>{label(e.status)}</Badge>
            </span>
            <span className="ink-2 block truncate text-xs">
              {e.channel === 'SMS' && e.bodyText ? e.bodyText.replace(/\s+/g, ' ') : e.fromAddress} · {dateTime(e.receivedAt)}
            </span>
            {e.note && <span className="mt-1 block text-xs text-amber-700 dark:text-amber-300">{e.note}</span>}
          </span>
        </button>
      ))}
    </List>
  )
}

function EmailView({ id }: { id: number; onDone: () => void }) {
  const e = useQuery({ queryKey: ['email', id], queryFn: () => api<Email>(`/emails/${id}`) })
  const reprocess = useMoneyMutation(() => api<Email>(`/emails/${id}/reprocess`, { method: 'POST' }))
  const skip = useMoneyMutation(() => api<{ learned: boolean; conflicts: number; alsoSkipped: number }>(`/emails/${id}/not-a-transaction`, { method: 'POST' }))
  const trust = useMoneyMutation(() => api<{ domain: string; recorded: number }>(`/emails/${id}/trust-sender`, { method: 'POST' }))
  const [teaching, setTeaching] = useState(false)
  const [taught, setTaught] = useState<TeachResult | null>(null)
  if (!e.data) return null
  const sms = e.data.channel === 'SMS'
  const teachable = e.data.status !== 'STATEMENT'
  const pending = e.data.status === 'NEEDS_REVIEW' || e.data.status === 'ERROR' || e.data.status === 'IGNORED'
  return (
    <div className="space-y-4">
      <div>
        <p className="font-semibold">{e.data.subject}</p>
        <p className="ink-2 text-xs">
          {e.data.fromAddress} · {dateTime(e.data.receivedAt)}
        </p>
        <div className="mt-2">
          <Badge tone={tone[e.data.status]}>{label(e.data.status)}</Badge>
        </div>
        {e.data.note && <p className="mt-2 text-sm text-amber-700 dark:text-amber-300">{e.data.note}</p>}
        {reprocess.data && (
          <div className="mt-2">
            <Notice tone={reprocess.data.status === 'PARSED' ? 'green' : 'amber'}>
              Result: {label(reprocess.data.status)}
              {reprocess.data.note ? ` — ${reprocess.data.note}` : ''}
            </Notice>
          </div>
        )}
      </div>
      {!teaching && <pre className="max-h-[45dvh] overflow-auto whitespace-pre-wrap rounded-[18px] bg-[var(--surface-2)] p-3 font-mono text-[11px] leading-relaxed">{e.data.bodyText}</pre>}
      {taught && (
        <Notice tone="green">
          {taught.txnId ? 'Recorded.' : 'Already recorded.'}
          {taught.learnedFormat && taught.mergedFormat
            ? ' Merged it with a format it already knew for this kind of message, so wording that differs between them is read too.'
            : taught.learnedFormat
            ? ' Learned this message format, so the next one like it is read automatically.'
            : taught.conflicts > 0
              ? ` Didn't learn this format: it would change how ${taught.conflicts} message${taught.conflicts > 1 ? 's' : ''} you taught earlier ${taught.conflicts > 1 ? 'are' : 'is'} read.`
              : ' The amount isn\'t in the text, so no format could be learned from it.'}
          {taught.learnedAccount ? ' Remembered which account it belongs to.' : ''}
          {taught.pausedFormat ? ' The format that misread it has been corrected too often and is paused (Settings → What it has learned).' : ''}
          {taught.alsoFixed > 0 ? ` ${taught.alsoFixed} other waiting message${taught.alsoFixed > 1 ? 's were' : ' was'} recorded too.` : ''}
        </Notice>
      )}
      {teachable && teaching && <TeachForm id={id} onDone={(r) => (setTaught(r), setTeaching(false))} />}
      {teachable && !teaching && !taught && !trust.data && e.data.hold !== 'UNKNOWN_SENDER' && (
        <Button variant={pending ? 'primary' : 'secondary'} className="w-full" onClick={() => setTeaching(true)}>
          <GraduationCap size={18} /> {e.data.status === 'PARSED' ? 'This was read wrong' : 'Tell me what this is'}
        </Button>
      )}
      {e.data.status === 'PARSED' && !teaching && !taught && !skip.data && (
        <button className="ink-2 w-full text-center text-sm font-medium" onClick={() => confirm('Remove its transaction and skip messages worded like this?') && skip.mutate(undefined)}>
          This isn't a transaction
        </button>
      )}
      {skip.data && (
        <Notice tone={skip.data.learned ? 'green' : 'amber'}>
          {skip.data.learned
            ? `Ignored. Messages worded like this are skipped from now on${skip.data.alsoSkipped > 0 ? `, and ${skip.data.alsoSkipped} waiting one${skip.data.alsoSkipped > 1 ? 's were' : ' was'} skipped too` : ''}.`
            : skip.data.conflicts > 0
              ? `Ignored, but not learned: you taught ${skip.data.conflicts > 1 ? `${skip.data.conflicts} messages` : 'a message'} worded like this as a transaction.`
              : 'Ignored.'}
        </Notice>
      )}
      {trust.data && (
        <Notice tone="green">
          Alerts from {trust.data.domain} are trusted now. {trust.data.recorded > 0 ? `${count(trust.data.recorded)} held email${trust.data.recorded > 1 ? 's were' : ' was'} recorded.` : ''}
        </Notice>
      )}
      {e.data.hold === 'UNKNOWN_SENDER' && e.data.status === 'NEEDS_REVIEW' && !trust.data && (
        <Button className="w-full" disabled={trust.isPending} onClick={() => trust.mutate(undefined)}>
          <ShieldCheck size={18} /> Trust {e.data.senderDomain}
        </Button>
      )}
      {pending && !teaching && !taught && !trust.data && (
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" onClick={() => reprocess.mutate(undefined)}>
            Try again
          </Button>
          {e.data.status !== 'IGNORED' && !skip.data && (
            <Button variant="secondary" className="flex-1" disabled={skip.isPending} onClick={() => skip.mutate(undefined)}>
              Not a transaction
            </Button>
          )}
        </div>
      )}
      <p className="ink-3 text-xs">
        {sms
          ? 'Correcting an SMS teaches the parser: it remembers the wording and the account, and re-checks everything else waiting for review.'
          : 'Correcting an email teaches the parser its format, so the next alert like it is read automatically. Its sender is trusted from then on.'}
      </p>
    </div>
  )
}

