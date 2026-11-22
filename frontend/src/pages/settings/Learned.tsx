import { useQuery } from '@tanstack/react-query'
import { GraduationCap, X } from '@phosphor-icons/react'
import type { ReactNode } from 'react'
import { api, type Learned } from '../../api'
import { Card, Confirm, FieldLabel, IconBadge, IconButton, TextButton } from '../../components/ui'
import { dateTime, plural, shortDate } from '../../format'
import { useMoneyMutation } from '../../hooks'

type Format = Learned['formats'][number]

/**
 * Everything the app has picked up from my corrections, each with a way to make it forget a mistake: message
 * wordings, payee names, and the email senders it trusts.
 */
export function LearnedCard() {
  const learned = useQuery({ queryKey: ['sms-learned'], queryFn: () => api<Learned>('/sms/learned') })
  const l = learned.data
  return (
    <Card className="p-5">
      <div className="flex items-center gap-3">
        <IconBadge icon={GraduationCap} />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">What it has learned</p>
          <p className="ink-2 text-caption">
            {l ? `${plural(l.formats.length, 'message wording')} · ${plural(l.payees.length, 'payee name')} · ${plural(l.senders, 'SMS sender')}` : ' '}
          </p>
        </div>
      </div>
      {l && (
        <div className="mt-4 space-y-5">
          {l.formats.length === 0 && l.payees.length === 0 && (
            <p className="ink-2 text-label leading-relaxed">
              Nothing yet. It learns when you correct a message (Settings → Messages), mark one as not a transaction, rename a payee on a transaction, or pick the
              account for a message it couldn't place.
            </p>
          )}
          {l.formats.length > 0 && <Formats formats={l.formats} />}
          {l.payees.length > 0 && <Payees payees={l.payees} />}
          <EmailSenders trusted={l.trustedDomains} banks={l.bankDomains} />
        </div>
      )}
    </Card>
  )
}

/** One thing it learned, with an ✕ that asks before it forgets. */
function ForgetRow({ ask, yes, label, busy, onForget, children, className }: { ask: string; yes: string; label: string; busy: boolean; onForget: () => void; children: ReactNode; className?: string }) {
  return (
    <li className={className ?? 'flex items-center gap-2 rounded-box bg-surface-2 px-3 py-2.5 text-small'}>
      <Confirm ask={ask} yes={yes} busy={busy} onYes={onForget}>
        {(confirm) => (
          <>
            {children}
            <IconButton icon={X} label={label} size="xs" variant="plain" onClick={confirm} />
          </>
        )}
      </Confirm>
    </li>
  )
}

function Formats({ formats }: { formats: Format[] }) {
  const forget = useMoneyMutation((id: number) => api(`/sms/formats/${id}`, { method: 'DELETE' }))
  const resume = useMoneyMutation((id: number) => api(`/sms/formats/${id}/resume`, { method: 'POST' }))
  return (
    <section>
      <FieldLabel>How messages are worded</FieldLabel>
      <ul className="space-y-2">
        {formats.map((f) => (
          <ForgetRow key={f.id} ask="Forget this wording?" yes="Forget" label="Forget this wording" busy={forget.isPending} onForget={() => forget.mutate(f.id)} className="flex items-start gap-2 rounded-box bg-surface-2 p-3">
            <div className="min-w-0 flex-1">
              <p className="break-words font-mono text-micro leading-relaxed">{f.skeleton}</p>
              <p className="ink-3 mt-1 text-micro">{formatDetails(f)}</p>
              {f.paused && (
                <p className="mt-1.5 flex items-center gap-2 text-micro text-attention">
                  Paused: it kept being corrected, so its messages wait for review.
                  <TextButton onClick={() => resume.mutate(f.id)}>Resume</TextButton>
                </p>
              )}
            </div>
          </ForgetRow>
        ))}
      </ul>
    </section>
  )
}

/** "Learned automatically · Email · HDFC · money out · used 4 times · last 3 Dec, 9:10 am · corrected once" */
function formatDetails(f: Format) {
  return [
    f.auto && 'Learned automatically',
    f.channel === 'EMAIL' && 'Email',
    f.kind === 'SKIP' ? 'not a transaction' : `${f.institution} · ${f.direction === 'DEBIT' ? 'money out' : 'money in'}`,
    `used ${plural(f.hits, 'time')}`,
    f.lastUsedAt && `last ${dateTime(f.lastUsedAt)}`,
    f.corrections > 0 && `corrected ${f.corrections === 1 ? 'once' : `${f.corrections} times`}`,
  ]
    .filter(Boolean)
    .join(' · ')
}

function Payees({ payees }: { payees: Learned['payees'] }) {
  const forget = useMoneyMutation((id: number) => api(`/sms/payees/${id}`, { method: 'DELETE' }))
  return (
    <section>
      <FieldLabel>Payee names</FieldLabel>
      <ul className="space-y-2">
        {payees.map((p) => (
          <ForgetRow key={p.id} ask="Forget this name?" yes="Forget" label="Forget this name" busy={forget.isPending} onForget={() => forget.mutate(p.id)}>
            <span className="ink-2 min-w-0 flex-1 truncate font-mono text-micro uppercase">{p.rawKey}</span>
            <span className="ink-3">→</span>
            <span className="min-w-0 flex-1 truncate font-medium">{p.displayName}</span>
          </ForgetRow>
        ))}
      </ul>
    </section>
  )
}

/** Who may send transaction alerts: each bank's own addresses (built in), then the senders I trusted. */
function EmailSenders({ trusted, banks }: { trusted: Learned['trustedDomains']; banks: Learned['bankDomains'] }) {
  const untrust = useMoneyMutation((domain: string) => api(`/trusted-senders/${encodeURIComponent(domain)}`, { method: 'DELETE' }))
  return (
    <section>
      <FieldLabel>Trusted email senders</FieldLabel>
      <ul className="divide-y divide-line rounded-box bg-surface-2 px-3">
        {banks.map((b) => (
          <li key={b.institution} className="py-2.5 text-small">
            <p className="font-medium">{b.name}</p>
            <p className="ink-2 mt-0.5 break-words text-caption">{b.domains.join(' · ')}</p>
          </li>
        ))}
      </ul>
      <p className="ink-3 mt-2 px-1 text-caption">Your banks' own addresses are built in and always trusted.</p>
      {trusted.length > 0 && (
        <ul className="mt-3 space-y-2">
          {trusted.map((t) => (
            <ForgetRow key={t.domain} ask={`Stop trusting ${t.domain}? Its alerts will wait for review.`} yes="Stop trusting" label={`Stop trusting ${t.domain}`} busy={untrust.isPending} onForget={() => untrust.mutate(t.domain)}>
              <span className="min-w-0 flex-1 truncate font-medium">{t.domain}</span>
              {t.since && <span className="ink-3 shrink-0 text-micro">trusted {shortDate(t.since)}</span>}
            </ForgetRow>
          ))}
        </ul>
      )}
      <p className="ink-3 mt-2 px-1 text-caption">
        {trusted.length > 0
          ? "Senders you trusted (from a held email, or by teaching one) can be removed; what was already recorded stays."
          : 'Senders you trust from a held email, or by teaching one, show up here.'}
      </p>
    </section>
  )
}
