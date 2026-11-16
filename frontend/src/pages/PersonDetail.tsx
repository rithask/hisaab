import { useQuery } from '@tanstack/react-query'
import { Check, PencilSimple, Receipt, Trash, Users, Wallet, X } from '@phosphor-icons/react'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, type Person, type Settlement, type Txn } from '../api'
import { Avatar, BigMoney, Button, Card, Confirm, Empty, ErrorText, Field, IconButton, Input, List, Page, PageHeader, PageSkeleton, Section, Segmented, Sheet, cx } from '../components/ui'
import { dateTime, fromLocalInput, money, toLocalInput } from '../format'
import { useMoneyMutation } from '../hooks'
import { DateTimePicker } from '../components/DatePicker'
import { PaidByForm, standing } from '../components/PaidBy'
import TxnSheet from '../components/TxnSheet'
import TxnRow from '../components/TxnRow'
import { PersonSheet } from './People'

interface Detail {
  person: Person
  shares: { shareId: number; amount: number; txn: Txn }[]
  settlements: Settlement[]
  paidForMe: Txn[]
}

/** One line of the history with someone; delta is what it does to what they owe me. */
interface Line {
  key: string
  at: string
  title: string
  sub: string
  delta: number
  txn?: Txn
  settlement?: Settlement
}

export default function PersonDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const d = useQuery({ queryKey: ['person', id], queryFn: () => api<Detail>(`/people/${id}`) })
  const [sheet, setSheet] = useState<'payment' | 'theyPaid' | 'iPaid' | 'edit' | null>(null)
  const [open, setOpen] = useState<Txn | null>(null)
  const remove = useMoneyMutation(() => api(`/people/${id}`, { method: 'DELETE' }), () => nav('/people'))
  const delSettlement = useMoneyMutation((sid: number) => api(`/settlements/${sid}`, { method: 'DELETE' }))
  const confirmSettlement = useMoneyMutation((sid: number) => api(`/settlements/${sid}/confirm`, { method: 'POST' }))
  const rejectSettlement = useMoneyMutation((sid: number) => api(`/settlements/${sid}/reject`, { method: 'POST' }))

  if (!d.data) return <PageSkeleton />
  const { person: p, shares, settlements, paidForMe } = d.data
  const first = p.name.split(' ')[0]

  const lines: Line[] = [
    ...shares.map((s) => ({
      key: `s${s.shareId}`,
      at: s.txn.occurredAt,
      title: s.txn.counterparty ?? s.txn.description ?? 'Payment',
      sub: `You paid ${money(s.txn.amount)} · ${first}'s share`,
      delta: s.amount,
      txn: s.txn,
    })),
    ...paidForMe.map((t) => ({
      key: `p${t.id}`,
      at: t.occurredAt,
      title: t.counterparty ?? t.category?.name ?? 'Something',
      sub: `${first} paid ${money(t.paidTotal ?? t.amount)} · your share`,
      delta: -t.amount,
      txn: t,
    })),
    ...settlements
      .filter((s) => s.status !== 'REJECTED')
      .map((s) => ({
        key: `m${s.id}`,
        at: s.settledAt,
        title: s.status === 'SUGGESTED' ? (s.direction === 'OUT' ? `Paying ${first} back?` : `${first} paying you back?`) : s.direction === 'OUT' ? `You paid ${first} back` : `${first} paid you back`,
        sub: [s.txnCounterparty && `“${s.txnCounterparty}”`, s.note].filter(Boolean).join(' · ') || 'Payment',
        delta: s.status === 'CONFIRMED' ? (s.direction === 'OUT' ? s.amount : -s.amount) : 0,
        settlement: s,
      })),
  ].sort((a, b) => b.at.localeCompare(a.at))

  const v = Math.round(p.outstanding * 100) / 100
  return (
    <Page>
      <PageHeader up={{ to: '/people', label: 'Splits' }} title="" action={<IconButton icon={PencilSimple} label="Edit" onClick={() => setSheet('edit')} />} />
      <div className="-mt-6 flex flex-col items-center text-center">
        <Avatar name={p.name} size={76} />
        <h1 className="mt-3 text-[24px] font-bold">{p.name}</h1>
        {p.upiName && <p className="ink-2 text-xs">UPI: {p.upiName}</p>}
      </div>

      <Card className="mt-5 p-5 text-center">
        <p className="ink-2 text-[13px] font-medium">{v > 0 ? `${first} owes you` : v < 0 ? `You owe ${first}` : 'All square'}</p>
        <BigMoney value={Math.abs(v)} className={v < 0 ? 'mt-1 text-[40px] leading-tight text-[var(--negative)]' : 'mt-1 text-[40px] leading-tight'} />
        <div className="mt-4 grid grid-cols-2 gap-2">
          <Button variant="secondary" onClick={() => setSheet('iPaid')}>
            <Receipt size={17} /> I paid
          </Button>
          <Button variant="secondary" onClick={() => setSheet('theyPaid')}>
            <Users size={17} /> {first} paid
          </Button>
        </div>
        {v !== 0 && (
          <Button className="mt-2 w-full" onClick={() => setSheet('payment')}>
            {v > 0 ? `${first} paid you back` : `You paid ${first} back`}
          </Button>
        )}
      </Card>

      <Section title="History">
        {lines.length === 0 ? (
          <Empty icon={Users} title="Nothing yet">
            When one of you pays for both, record it with “I paid” or “{first} paid”. The balance above keeps track.
          </Empty>
        ) : (
          <>
            <List>
              {lines.map((l) => (
                <div key={l.key} className="flex items-center gap-3 px-4 py-3">
                  <Confirm ask="Remove this payment?" yes="Remove" busy={delSettlement.isPending} onYes={() => l.settlement && delSettlement.mutate(l.settlement.id)}>
                  {(ask) => (<>
                  <button className="min-w-0 flex-1 text-left" disabled={!l.txn} onClick={() => l.txn && setOpen(l.txn)}>
                    <p className="truncate text-[15px] font-semibold">{l.title}</p>
                    <p className="ink-2 truncate text-xs">
                      {dateTime(l.at)} · {l.sub}
                    </p>
                  </button>
                  {l.settlement && l.settlement.status === 'SUGGESTED' ? (
                    <span className="flex shrink-0 items-center gap-1.5">
                      <span className="tabular mr-1 text-[15px] font-semibold">{money(l.settlement.amount)}?</span>
                      <button className="pressable flex h-9 w-9 items-center justify-center rounded-full bg-[var(--surface-2)]" onClick={() => rejectSettlement.mutate(l.settlement!.id)} aria-label="Not a repayment">
                        <X size={16} />
                      </button>
                      <button className="pressable flex h-9 w-9 items-center justify-center rounded-full accent-fill" onClick={() => confirmSettlement.mutate(l.settlement!.id)} aria-label="Confirm repayment">
                        <Check size={16} />
                      </button>
                    </span>
                  ) : (
                    <span className="tabular shrink-0 text-[15px] font-semibold">
                      {l.delta >= 0 ? '+' : '−'}
                      {money(Math.abs(l.delta))}
                    </span>
                  )}
                  {l.settlement && l.settlement.status === 'CONFIRMED' && (
                    <button className="ink-3 p-1" onClick={ask} aria-label="Remove payment">
                      <Trash size={16} />
                    </button>
                  )}
                  </>)}
                  </Confirm>
                </div>
              ))}
            </List>
            <p className="ink-3 mt-2 px-1 text-xs">+ adds to what {first} owes you; − takes it off.</p>
          </>
        )}
      </Section>

      <Confirm className="mt-8" ask={`Delete ${p.name} and their split history?`} yes="Delete" busy={remove.isPending} onYes={() => remove.mutate(undefined)}>
        {(ask) => (
          <Button variant="danger" className="mt-8 w-full" onClick={ask}>
            Delete person
          </Button>
        )}
      </Confirm>
      <ErrorText error={remove.error} />

      <Sheet open={sheet === 'payment'} onClose={() => setSheet(null)} title="Record a payment">
        {sheet === 'payment' && <PaymentForm onClose={() => setSheet(null)} person={p} />}
      </Sheet>
      <Sheet open={sheet === 'theyPaid'} onClose={() => setSheet(null)} title={`${first} paid`}>
        {sheet === 'theyPaid' && <PaidByForm person={p} onDone={() => setSheet(null)} />}
      </Sheet>
      <Sheet open={sheet === 'iPaid'} onClose={() => setSheet(null)} title="Which payment?">
        {sheet === 'iPaid' && <PickPayment first={first} onPick={(t) => (setSheet(null), setOpen(t))} />}
      </Sheet>
      <PersonSheet open={sheet === 'edit'} onClose={() => setSheet(null)} person={p} />
      <TxnSheet txn={open} onClose={() => setOpen(null)} />
    </Page>
  )
}

/** "I paid": pick the payment to split; its sheet has the split editor. */
function PickPayment({ first, onPick }: { first: string; onPick: (t: Txn) => void }) {
  const q = useQuery({ queryKey: ['transactions', 'to-split'], queryFn: () => api<{ items: Txn[] }>('/transactions?direction=DEBIT&size=40') })
  const list = (q.data?.items ?? []).filter((t) => !t.paidBy && !t.transfer && !t.sharedAmount)
  return (
    <div>
      <p className="ink-2 mb-3 px-1 text-sm">Pick what you paid for, then tap Split and choose {first}.</p>
      <List>
        {list.map((t) => (
          <TxnRow key={t.id} t={t} onClick={() => onPick(t)} showDate />
        ))}
        {q.data && list.length === 0 && <p className="ink-2 p-5 text-center text-sm">No recent payments to split.</p>}
      </List>
    </div>
  )
}

/**
 * Record a repayment. Pick the money it came in (or went out) with, so it stops counting as income or spending,
 * or "Cash or another account" for money the app never saw.
 */
function PaymentForm({ onClose, person }: { onClose: () => void; person: Person }) {
  const first = person.name.split(' ')[0]
  const [direction, setDirection] = useState<'IN' | 'OUT'>(person.outstanding < 0 ? 'OUT' : 'IN')
  const due = Math.abs(person.outstanding)
  const [txn, setTxn] = useState<Txn | null>(null)
  const [amount, setAmount] = useState(String(due))
  const [when, setWhen] = useState(toLocalInput())
  const [note, setNote] = useState('')
  const open = useQuery({ queryKey: ['open-payments', person.id, direction], queryFn: () => api<Txn[]>(`/people/${person.id}/open-payments?direction=${direction}`) })
  const value = Number(amount)
  const choose = (t: Txn | null) => {
    setTxn(t)
    setAmount(String(t ? Math.min(t.amount, due || t.amount) : due))
  }
  const switchDirection = (d: 'IN' | 'OUT') => (setDirection(d), choose(null))
  const save = useMoneyMutation(
    () => api('/settlements', { body: { personId: person.id, direction, amount: value, txnId: txn?.id, settledAt: txn ? undefined : fromLocalInput(when), note } }),
    onClose,
  )
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      <Segmented<'IN' | 'OUT'>
        value={direction}
        onChange={switchDirection}
        options={[
          { value: 'IN', label: `${first} paid you` },
          { value: 'OUT', label: `You paid ${first}` },
        ]}
      />
      <div>
        <p className="ink-2 mb-2 px-1 text-[13px] font-medium">{direction === 'IN' ? 'Which money was it?' : 'Which payment was it?'}</p>
        <List>
          {(open.data ?? []).slice(0, 8).map((t) => (
            <button key={t.id} type="button" onClick={() => choose(t)} className={cx('flex w-full items-center gap-3 px-4 py-3 text-left row', txn?.id === t.id && 'bg-[var(--surface-2)]')}>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[15px] font-medium">{t.counterparty ?? t.description ?? (direction === 'IN' ? 'Money received' : 'Payment')}</span>
                <span className="ink-2 block truncate text-xs">
                  {dateTime(t.occurredAt)} · {t.account?.name}
                </span>
              </span>
              <span className="tabular shrink-0 text-[15px] font-semibold">{money(t.amount)}</span>
              {txn?.id === t.id && <Check size={18} className="shrink-0" />}
            </button>
          ))}
          <button type="button" onClick={() => choose(null)} className={cx('flex w-full items-center gap-3 px-4 py-3 text-left row', !txn && 'bg-[var(--surface-2)]')}>
            <Wallet size={19} className="ink-2 shrink-0" />
            <span className="flex-1 text-[15px] font-medium">Cash or another account</span>
            {!txn && <Check size={18} className="shrink-0" />}
          </button>
        </List>
      </div>
      <Field label="Amount">
        <Input required inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
      </Field>
      {!txn && (
        <>
          <Field label="When">
            <DateTimePicker value={when} onChange={setWhen} max={toLocalInput()} label="When" />
          </Field>
          <Field label="Note">
            <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Cash, GPay…" />
          </Field>
        </>
      )}
      {value > 0 && (
        <p className="ink-2 rounded-2xl bg-[var(--surface-2)] px-4 py-3 text-[13px]">
          {standing(person.outstanding, first)} → <b className="text-[var(--ink)]">{standing(person.outstanding + (direction === 'OUT' ? value : -value), first)}</b>
        </p>
      )}
      <ErrorText error={save.error} />
      <Button type="submit" className="w-full" disabled={save.isPending || !(value > 0) || (!!txn && value > txn.amount)}>
        Save payment
      </Button>
    </form>
  )
}
