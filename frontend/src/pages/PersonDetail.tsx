import { useQuery } from '@tanstack/react-query'
import { Check, HandCoins, PencilSimple, Receipt, Trash, Users, Wallet, X } from '@phosphor-icons/react'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, type Person, type Settlement, type Txn } from '../api'
import { Avatar, BigMoney, Button, Card, Confirm, Fold, Empty, ErrorText, IconButton, Input, List, Page, PageHeader, PageSkeleton, Section, Sheet } from '../components/ui'
import { dateTime, fromLocalInput, money, toLocalInput, firstName } from '../format'
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
  const first = firstName(p.name)

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
        <h1 className="mt-3 text-subheading font-bold">{p.name}</h1>
        {p.upiName && <p className="ink-2 text-caption">UPI: {p.upiName}</p>}
      </div>

      <Card className="mt-5 p-5 text-center">
        <p className="ink-2 text-small font-medium">{v > 0 ? `${first} owes you` : v < 0 ? `You owe ${first}` : 'All square'}</p>
        <BigMoney value={Math.abs(v)} className={v < 0 ? 'mt-1 text-display-sm leading-tight text-negative' : 'mt-1 text-display-sm leading-tight'} />
        {v !== 0 && (
          <Button className="mt-4 w-full" onClick={() => setSheet('payment')}>
            Settle up
          </Button>
        )}
        <div className="mt-2 grid grid-cols-2 gap-2">
          <Button variant="secondary" onClick={() => setSheet('iPaid')}>
            <Receipt size={17} /> Split a payment
          </Button>
          <Button variant="secondary" onClick={() => setSheet('theyPaid')}>
            <Users size={17} /> {first} paid
          </Button>
        </div>
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
                    <p className="truncate text-body font-semibold">{l.title}</p>
                    <p className="ink-2 truncate text-caption">
                      {dateTime(l.at)} · {l.sub}
                    </p>
                  </button>
                  {l.settlement && l.settlement.status === 'SUGGESTED' ? (
                    <span className="flex shrink-0 items-center gap-1.5">
                      <span className="tabular mr-1 text-body font-semibold">{money(l.settlement.amount)}?</span>
                      <IconButton icon={X} label="Not a repayment" size="sm" onClick={() => rejectSettlement.mutate(l.settlement!.id)} />
                      <IconButton icon={Check} label="Confirm repayment" size="sm" variant="primary" onClick={() => confirmSettlement.mutate(l.settlement!.id)} />
                    </span>
                  ) : (
                    <span className="tabular shrink-0 text-body font-semibold">
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
            <p className="ink-3 mt-2 px-1 text-caption">+ adds to what {first} owes you; − takes it off.</p>
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

      <SettleSheet person={sheet === 'payment' ? p : null} onClose={() => setSheet(null)} />
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

/** "Split a payment": pick one of my payments; its sheet has the split editor. */
function PickPayment({ first, onPick }: { first: string; onPick: (t: Txn) => void }) {
  const q = useQuery({ queryKey: ['transactions', 'to-split'], queryFn: () => api<{ items: Txn[] }>('/transactions?direction=DEBIT&size=40') })
  const list = (q.data?.items ?? []).filter((t) => !t.paidBy && !t.transfer && !t.sharedAmount)
  return (
    <div>
      <p className="ink-2 mb-3 px-1 text-label">Pick what you paid for, then tap Split with friends and choose {first}.</p>
      <List>
        {list.map((t) => (
          <TxnRow key={t.id} t={t} onClick={() => onPick(t)} showDate />
        ))}
        {q.data && list.length === 0 && <p className="ink-2 p-5 text-center text-label">No recent payments to split.</p>}
      </List>
    </div>
  )
}

/** "Settle up" with someone, from the Splits list or their page. */
export function SettleSheet({ person, onClose }: { person: Person | null; onClose: () => void }) {
  return (
    <Sheet open={!!person} onClose={onClose} title={person ? `Settle up with ${firstName(person.name)}` : ''}>
      {person && <SettleForm person={person} onClose={onClose} />}
    </Sheet>
  )
}

/**
 * Settling up is one tap: it says where things stand, picks the money that most likely settled it (the app's best
 * match by sender name, then by amount), and offers one button. The amount, the direction, a different payment or
 * cash are there to change, but folded away. Linking the payment stops it counting as income or spending.
 */
function SettleForm({ onClose, person }: { onClose: () => void; person: Person }) {
  const first = firstName(person.name)
  const [direction, setDirection] = useState<'IN' | 'OUT'>(person.outstanding < 0 ? 'OUT' : 'IN')
  const due = Math.abs(person.outstanding)
  const open = useQuery({ queryKey: ['open-payments', person.id, direction], queryFn: () => api<Txn[]>(`/people/${person.id}/open-payments?direction=${direction}`) })
  // undefined: not chosen yet (use the best match); null: cash or another account
  const [picked, setPicked] = useState<Txn | null | undefined>(undefined)
  const best = (open.data ?? []).find((t) => matches(t, person, due))
  const txn = picked === undefined ? (best ?? null) : picked
  const [amount, setAmount] = useState<string | null>(null)
  const value = amount != null ? Number(amount) : txn ? Math.min(txn.amount, due || txn.amount) : due
  const [changing, setChanging] = useState<'amount' | 'payment' | null>(null)
  const [when, setWhen] = useState(toLocalInput())
  const [note, setNote] = useState('')
  const save = useMoneyMutation(
    () => api('/settlements', { body: { personId: person.id, direction, amount: value, txnId: txn?.id, settledAt: txn ? undefined : fromLocalInput(when), note } }),
    onClose,
  )
  const flip = () => (setDirection(direction === 'IN' ? 'OUT' : 'IN'), setPicked(undefined), setAmount(null))
  if (!open.data) return <div className="h-48 animate-pulse rounded-card bg-surface-2" />

  const others = open.data.filter((t) => t.id !== txn?.id).slice(0, 6)
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      <div className="text-center">
        <p className="ink-2 text-body">{direction === 'IN' ? `${first} pays you` : `You pay ${first}`}</p>
        <button type="button" onClick={() => setChanging(changing === 'amount' ? null : 'amount')} className="mt-1 inline-flex items-baseline gap-2">
          <BigMoney value={value} className="text-display-sm leading-tight" />
          <PencilSimple size={16} className="ink-3" />
        </button>
        <Fold open={changing === 'amount'}>
          <div className="mx-auto mt-2 max-w-[200px]">
            <Input autoFocus inputMode="decimal" aria-label="Amount" className="text-center" value={amount ?? String(value)} onChange={(e) => setAmount(e.target.value)} />
          </div>
        </Fold>
        <p className="ink-2 mt-1 text-small">
          {standing(person.outstanding, first)} → <b className="text-ink">{standing(person.outstanding + (direction === 'OUT' ? value : -value), first)}</b>
        </p>
      </div>

      {/* The money it was: the best match is picked already; anything else is one tap away. */}
      <button type="button" onClick={() => setChanging(changing === 'payment' ? null : 'payment')} className="row flex w-full items-center gap-3 rounded-card bg-surface-2 px-4 py-3 text-left">
        {txn ? <HandCoins size={20} className="shrink-0" /> : <Wallet size={20} className="shrink-0" />}
        <span className="min-w-0 flex-1">
          <span className="block truncate text-body font-medium">{txn ? (txn.counterparty ?? txn.description ?? 'Payment') : 'Cash or another account'}</span>
          <span className="ink-2 block truncate text-caption">
            {txn ? `${money(txn.amount)} · ${dateTime(txn.occurredAt)} · ${txn.account?.name ?? ''}` : 'Money the app didn\'t see'}
          </span>
        </span>
        <span className="shrink-0 text-label font-medium text-accent">Change</span>
      </button>
      <Fold open={changing === 'payment'}>
        <List >
          {others.map((t) => (
            <button key={t.id} type="button" onClick={() => (setPicked(t), setAmount(null), setChanging(null))} className="row flex w-full items-center gap-3 px-4 py-3 text-left">
              <span className="min-w-0 flex-1">
                <span className="block truncate text-body font-medium">{t.counterparty ?? t.description ?? (direction === 'IN' ? 'Money received' : 'Payment')}</span>
                <span className="ink-2 block truncate text-caption">
                  {dateTime(t.occurredAt)} · {t.account?.name}
                </span>
              </span>
              <span className="tabular shrink-0 text-body font-semibold">{money(t.amount)}</span>
            </button>
          ))}
          {txn && (
            <button type="button" onClick={() => (setPicked(null), setAmount(null), setChanging(null))} className="row flex w-full items-center gap-3 px-4 py-3 text-left">
              <Wallet size={19} className="ink-2 shrink-0" />
              <span className="flex-1 text-body font-medium">Cash or another account</span>
            </button>
          )}
        </List>
      </Fold>
      {!txn && (
        <div className="space-y-2">
          <DateTimePicker value={when} onChange={setWhen} max={toLocalInput()} label="When" />
          <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional): cash, GPay…" aria-label="Note" />
        </div>
      )}

      <ErrorText error={save.error} />
      <Button type="submit" className="w-full" disabled={save.isPending || !(value > 0) || (!!txn && value > txn.amount)}>
        Settle {money(value)}
      </Button>
      <button type="button" className="link ink-2 mx-auto block text-small font-medium" onClick={flip}>
        {direction === 'IN' ? `It was you paying ${first}` : `It was ${first} paying you`}
      </button>
    </form>
  )
}

/** A payment the app is confident settles it: from someone with their name, or exactly the amount due. */
function matches(t: Txn, p: Person, due: number) {
  const who = (t.counterparty ?? '').trim().toLowerCase()
  const names = [p.name, p.upiName].filter((n): n is string => !!n).map((n) => n.toLowerCase())
  const named = who.length >= 3 && names.some((n) => who.includes(n) || n.includes(who))
  return named || Math.abs(t.amount - due) < 0.01
}
