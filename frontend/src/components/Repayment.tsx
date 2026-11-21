import { useQuery } from '@tanstack/react-query'
import { ArrowUUpLeft, Check, HandCoins, X } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type Person, type Settlement, type Txn } from '../api'
import { money, firstName } from '../format'
import { useMoneyMutation, usePeople } from '../hooks'
import { standing } from './PaidBy'
import { Avatar, Button, cx, ErrorText, Field, Input, MoreRow, Sheet, IconButton } from './ui'

export type Recorded = { settlement: Settlement; rememberedName?: string }

/**
 * On money in or out of my accounts: is it someone settling up? Money in can be someone paying me back; money
 * out can be me paying back someone I owe. Linked money stops counting as income or spending.
 */
/**
 * @param part "linked": only repayments already linked to this payment; "ask": only the offer to link one (shown
 *             under More). Both when left out.
 */
export function RepaymentRow({ txn, part }: { txn: Txn; part?: 'linked' | 'ask' }) {
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
  if (part === 'linked' && list.length === 0) return null
  if (part === 'ask' && list.length > 0) return null

  return (
    <div className="space-y-2">
      {list.map((s) =>
        s.status === 'SUGGESTED' ? (
          <div key={s.id} className="anim-pop flex items-center gap-3 rounded-box bg-accent-soft p-3">
            <Avatar name={s.personName} size={36} />
            <p className="min-w-0 flex-1 text-label">
              {s.direction === 'OUT' ? `Paying ${firstName(s.personName)} back` : `${firstName(s.personName)} paying you back`} <b className="tabular">{money(s.amount)}</b>?
            </p>
            <IconButton icon={X} label="No" size="sm" variant="raised" onClick={() => reject.mutate(s)} />
            <IconButton icon={Check} label="Yes" size="sm" variant="primary" onClick={() => confirm.mutate(s)} />
          </div>
        ) : (
          <div key={s.id} className="flex items-center gap-3 rounded-box bg-surface-2 p-3 text-label">
            <HandCoins size={18} className="shrink-0" />
            <p className="min-w-0 flex-1">
              {s.direction === 'OUT' ? `You paid ${firstName(s.personName)} back` : `${firstName(s.personName)} paid you back`} <b className="tabular">{money(s.amount)}</b>
              <span className="ink-2 block text-caption">Not counted as {s.direction === 'OUT' ? 'spending' : 'income'}.</span>
            </p>
            <button className="link ink-2 shrink-0 text-small font-medium" onClick={() => undo.mutate(s)}>
              Undo
            </button>
          </div>
        ),
      )}
      {list.length === 0 && (
        <MoreRow icon={ArrowUUpLeft} onClick={() => setPicking(true)}>
          {incoming ? 'Someone paying you back?' : 'Paying someone back?'}
        </MoreRow>
      )}
      {remembered && (
        <p className="anim-pop rounded-box bg-accent-soft px-3 py-2 text-caption text-accent">
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
          <button key={p.id} type="button" onClick={() => pick(p)} className={cx('pressable flex w-full items-center gap-3 rounded-box px-2 py-2 text-left', person?.id === p.id && 'bg-surface-2')}>
            <Avatar name={p.name} size={40} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-body font-semibold">{p.name}</span>
              <span className={cx('block text-caption', due(p) > 0 ? 'text-ink-2' : 'ink-3')}>{standing(p.outstanding)}</span>
            </span>
            {person?.id === p.id && <Check size={18} />}
          </button>
        ))}
      </div>
      {person && (
        <>
          <Field label={incoming ? `How much of the ${money(txn.amount)} was ${firstName(person.name)} paying back?` : `How much of the ${money(txn.amount)} was paying ${person.name.split(' ')[0]} back?`}>
            <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))} />
          </Field>
          {value > 0 && (
            <p className="ink-2 rounded-box bg-surface-2 px-4 py-3 text-small">
              <b className="text-ink">{person.name}</b>: {standing(person.outstanding)} → <b className="text-ink">{standing(person.outstanding + (incoming ? -value : value))}</b>
            </p>
          )}
        </>
      )}
      <ErrorText error={save.error} />
      <Button className="w-full" disabled={!person || !(value > 0) || value > txn.amount || save.isPending} onClick={() => save.mutate(undefined)}>
        {person ? `Link to ${firstName(person.name)}` : 'Pick who it was'}
      </Button>
    </div>
  )
}
