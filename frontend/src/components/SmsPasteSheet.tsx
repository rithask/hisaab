import { ArrowDownLeft, ArrowUpRight, CheckCircle, Copy, WarningCircle } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type SmsResult } from '../api'
import { money } from '../format'
import { useMoneyMutation } from '../hooks'
import { Button, ErrorText, Field, Input, Sheet, Textarea, IconBadge, InfoBox } from './ui'

/** Paste a bank SMS by hand (same parser and endpoint the phone automation uses). */
/** `initialText`: an SMS shared into the app from another app (Android share sheet). */
export default function SmsPasteSheet({ open, onClose, initialText }: { open: boolean; onClose: () => void; initialText?: string }) {
  const [text, setText] = useState(initialText ?? '')
  const [sender, setSender] = useState('')
  const send = useMoneyMutation(() => api<SmsResult>('/sms', { body: { text, sender: sender.trim() || undefined } }))
  const close = () => {
    onClose()
    setTimeout(() => (send.reset(), setText(''), setSender('')), 200)
  }
  const r = send.data

  return (
    <Sheet open={open} onClose={close} title="Add from SMS">
      {r ? (
        <div className="space-y-4">
          <SmsResultCard r={r} />
          <div className="flex gap-2">
            <Button variant="secondary" className="flex-1" onClick={() => (send.reset(), setText(''))}>
              Paste another
            </Button>
            <Button className="flex-1" onClick={close}>
              Done
            </Button>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          <Field label="Message" hint="Paste the whole SMS from your bank. Amount, account, payee, date and reference are read from it.">
            <Textarea rows={6} autoFocus value={text} onChange={(e) => setText(e.target.value)} placeholder="Sent Rs.500.00 From HDFC Bank A/C *2286 To RAHUL On 05/11/26 Ref 5312…" />
          </Field>
          <Field label="Sender (optional)" hint="e.g. AX-HDFCBK. Helps recognise the bank.">
            <Input value={sender} onChange={(e) => setSender(e.target.value)} placeholder="AX-HDFCBK" autoCapitalize="characters" />
          </Field>
          <ErrorText error={send.error} />
          <Button className="w-full" disabled={!text.trim() || send.isPending} onClick={() => send.mutate(undefined)}>
            {send.isPending ? 'Reading…' : 'Add transaction'}
          </Button>
        </div>
      )}
    </Sheet>
  )
}

export function SmsResultCard({ r }: { r: SmsResult }) {
  const t = r.transaction
  const p = r.parsed
  const ok = r.status === 'PARSED'
  const Icon = ok ? CheckCircle : WarningCircle
  return (
    <div className="space-y-3">
      <InfoBox icon={Icon} tone={ok ? 'positive' : 'attention'} title={ok ? 'Recorded' : r.status === 'DUPLICATE' ? 'Already recorded' : r.status === 'IGNORED' ? 'Not a transaction' : 'Needs attention'}>
        <span className="text-ink-2">{r.message}</span>
      </InfoBox>
      {(t || p) && (
        <div className="surface rounded-card p-4">
          <div className="flex items-center gap-3">
            {(t?.direction ?? p?.direction) === 'CREDIT' ? <IconBadge icon={ArrowDownLeft} tone="positive" /> : <IconBadge icon={ArrowUpRight} />}
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold">{t?.counterparty ?? p?.counterparty ?? 'Unknown payee'}</p>
              <p className="ink-2 truncate text-caption">{t ? [t.account, t.category].filter(Boolean).join(' · ') : `${p!.bank} ${p!.accountType === 'CREDIT_CARD' ? 'card' : 'account'}${p!.last4 ? ` ••${p!.last4}` : ''}`}</p>
            </div>
            <p className="tabular text-title font-semibold">
              {(t?.direction ?? p?.direction) === 'CREDIT' ? '+' : '−'}
              {p && p.currency !== 'INR' ? `${p.currency} ${p.amount}` : money(t?.amount ?? p!.amount)}
            </p>
          </div>
          {p && (
            <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-line pt-3 text-caption">
              <Detail k="Bank" v={p.bank} />
              <Detail k="Account" v={p.last4 ? `•••• ${p.last4}` : '—'} />
              <Detail k="Date" v={[p.date, p.time?.slice(0, 5)].filter(Boolean).join(' ') || 'from SMS time'} />
              <Detail k="Reference" v={p.reference ?? '—'} />
              {p.balance != null && <Detail k="Balance after" v={money(p.balance)} />}
            </dl>
          )}
        </div>
      )}
    </div>
  )
}

function Detail({ k, v }: { k: string; v: string }) {
  return (
    <div className="min-w-0">
      <dt className="ink-3">{k}</dt>
      <dd className="tabular truncate font-medium">{v}</dd>
    </div>
  )
}

export function CopyButton({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [done, setDone] = useState(false)
  return (
    <Button
      size="sm"
      variant="secondary"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value)
        } catch {
          // Clipboard API needs HTTPS on phones; fall back to a selectable prompt.
          window.prompt('Copy this:', value)
        }
        setDone(true)
        setTimeout(() => setDone(false), 1500)
      }}
    >
      <Copy size={14} /> {done ? 'Copied' : label}
    </Button>
  )
}

