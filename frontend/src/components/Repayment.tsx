import { useQuery } from '@tanstack/react-query'
import { ArrowUUpLeft, Check, HandCoins, X } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type Person, type Settlement, type Txn } from '../api'
import { money } from '../format'
import { useMoneyMutation, usePeople } from '../hooks'
import { standing } from './PaidBy'
import { Avatar, Button, cx, ErrorText, Field, Input, Sheet } from './ui'

export type Recorded = { settlement: Settlement; rememberedName?: string }

/**
 * On money in or out of my accounts: is it someone settling up? Money in can be someone paying me back; money
 * out can be me paying back someone I owe. Linked money stops counting as income or spending.
 */
export function RepaymentRow({ txn }: { txn: Txn }) {
  const incoming = txn.direction === 'CREDIT'
  const people = usePeople()
  const linked = useQuery({ queryKey: ['repayments', txn.id], queryFn: () => api<Settlement[]>(`/transactions/${txn.id}/repayments`) })
  const [picking, setPicking] = useState(false)
  // the sender's name the app now knows the person by, after a link
  const [remembered, setRemembered] = useState<{ person: string; name: string } | null>(null)
  const learnt = (r: Recorded) => setRemembered(r.rememberedName ? { person: r.settlement.personName, name: r.rememberedName } : null)
  const confirm = useMoneyMutation((s: Settlement) => api<Recorded>(`/settlements/${s.id}/confirm`, { method: 'POST' }), learnt)
  const reject = useMoneyMutation((s: Settlement) => api(`/settlements/${s.id}/reject`, { method: 'POST' }))
  const undo = useMoneyMutation((s: Settlement) => api(`/settlements/${s.id}`, { method: 'DELETE' }))

  if (!linked.data || !people.data) return null
  const list = linked.data
  // Money out is only offered as a repayment when I owe someone; otherwise the question is noise.
  const someoneToPay = people.data.some((p) => p.outstanding < 0)
  if (list.length === 0 && !incoming && !someoneToPay) return null
  if (list.length === 0 && people.data.length === 0) return null
  const first = (n: string) => n.split(' ')[0]

  return (
    <div className="space-y-2">
      {list.map((s) =>
        s.status === 'SUGGESTED' ? (
          <div key={s.id} className="anim-pop flex items-center gap-3 rounded-2xl bg-[var(--accent-soft)] p-3">
            <Avatar name={s.personName} size={36} />
            <p className="min-w-0 flex-1 text-sm">
              {s.direction === 'OUT' ? `Paying ${first(s.personName)} back` : `${first(s.personName)} paying you back`} <b className="tabular">{money(s.amount)}</b>?
            </p>
            <button className="pressable flex h-9 w-9 items-center justify-center rounded-full bg-[var(--surface)]" onClick={() => reject.mutate(s)} aria-label="No">
              <X size={17} />
            </button>
            <button className="pressable flex h-9 w-9 items-center justify-center rounded-full accent-fill" onClick={() => confirm.mutate(s)} aria-label="Yes">
              <Check size={17} />
            </button>
          </div>
        ) : (
          <div key={s.id} className="flex items-center gap-3 rounded-2xl bg-[var(--surface-2)] p-3 text-sm">
            <HandCoins size={18} className="shrink-0" />
            <p className="min-w-0 flex-1">
              {s.direction === 'OUT' ? `You paid ${first(s.personName)} back` : `${first(s.personName)} paid you back`} <b className="tabular">{money(s.amount)}</b>
              <span className="ink-2 block text-xs">Not counted as {s.direction === 'OUT' ? 'spending' : 'income'}.</span>
            </p>
            <button className="link ink-2 shrink-0 text-[13px] font-medium" onClick={() => undo.mutate(s)}>
              Undo
            </button>
          </div>
        ),
      )}
      {list.length === 0 && (
        <button className="ink-2 flex items-center gap-1.5 px-1 text-sm font-medium" onClick={() => setPicking(true)}>
          <ArrowUUpLeft size={15} /> {incoming ? 'Repayment from someone?' : 'Paying someone back?'}
        </button>
      )}
      {remembered && (
        <p className="anim-pop rounded-2xl bg-[var(--accent-soft)] px-3 py-2 text-xs text-[var(--accent)]">
          Remembered “{remembered.name}” as {remembered.person}, so their next payment is matched by name.
        </p>
      )}
      <ErrorText error={confirm.error ?? reject.error ?? undo.error} />
      <Sheet open={picking} onClose={() => setPicking(false)} title={incoming ? 'Who paid you back?' : 'Who are you paying back?'}>
        {picking && <LinkForm txn={txn} people={people.data} onDone={(r) => (setPicking(false), learnt(r))} />}
      </Sheet>
    </div>
  )
}

/** Pick the person (those it most likely is first), check the amount, link. */
function LinkForm({ txn, people, onDone }: { txn: Txn; people: Person[]; onDone: (r: Recorded) => void }) {
  const incoming = txn.direction === 'CREDIT'
  // what's open from this money's side: what they owe me (in) or what I owe them (out)
  const due = (p: Person) => (incoming ? p.outstanding : -p.outstanding)
  const sorted = [...people].sort((a, b) => due(b) - due(a))
  const [person, setPerson] = useState<Person | null>(null)
  const [amount, setAmount] = useState('')
  const pick = (p: Person) => {
    setPerson(p)
    const d = due(p)
    setAmount(String(d > 0 ? Math.min(txn.amount, d) : txn.amount))
  }
  const value = Number(amount)
  const save = useMoneyMutation(
    () => api<Recorded>('/settlements', { body: { personId: person!.id, direction: incoming ? 'IN' : 'OUT', amount: value, txnId: txn.id } }),
    onDone,
  )

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        {sorted.map((p) => (
          <button key={p.id} type="button" onClick={() => pick(p)} className={cx('pressable flex w-full items-center gap-3 rounded-2xl px-2 py-2 text-left', person?.id === p.id && 'bg-[var(--surface-2)]')}>
            <Avatar name={p.name} size={40} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[15px] font-semibold">{p.name}</span>
              <span className={cx('block text-xs', due(p) > 0 ? 'text-[var(--ink-2)]' : 'ink-3')}>{standing(p.outstanding)}</span>
            </span>
            {person?.id === p.id && <Check size={18} />}
          </button>
        ))}
      </div>
      {person && (
        <>
          <Field label={incoming ? `How much of the ${money(txn.amount)} was ${person.name.split(' ')[0]} paying back?` : `How much of the ${money(txn.amount)} was paying ${person.name.split(' ')[0]} back?`}>
            <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))} />
          </Field>
          {value > 0 && (
            <p className="ink-2 rounded-2xl bg-[var(--surface-2)] px-4 py-3 text-[13px]">
              <b className="text-[var(--ink)]">{person.name}</b>: {standing(person.outstanding)} → <b className="text-[var(--ink)]">{standing(person.outstanding + (incoming ? -value : value))}</b>
            </p>
          )}
        </>
      )}
      <ErrorText error={save.error} />
      <Button className="w-full" disabled={!person || !(value > 0) || value > txn.amount || save.isPending} onClick={() => save.mutate(undefined)}>
        {person ? `Link to ${person.name.split(' ')[0]}` : 'Pick who it was'}
      </Button>
    </div>
  )
}
