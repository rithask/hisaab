import { useQuery } from '@tanstack/react-query'
import { Check, Users } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { api, type Split, type Txn } from '../api'
import { money } from '../format'
import { useMoneyMutation, usePeople } from '../hooks'
import { Button, cx, ErrorText, Input, Segmented, Toggle, MoreRow } from './ui'
import { QUICK } from '../motion'


export type Method = 'EQUAL' | 'EXACT' | 'PERCENT'

/** @param part "linked": only an existing split; "ask": only the offer to split (under More). */
export function SplitEditor({ txn, part }: { txn: Txn; part: 'linked' | 'ask' }) {
  const people = usePeople()
  const split = useQuery({ queryKey: ['split', txn.id], queryFn: () => api<Split>(`/transactions/${txn.id}/split`) })
  const [editing, setEditing] = useState(false)
  const [method, setMethod] = useState<Method>('EQUAL')
  const [includeMe, setIncludeMe] = useState(true)
  const [values, setValues] = useState<Record<number, string>>({})

  useEffect(() => {
    if (!split.data || split.data.shares.length === 0) return
    const m = split.data.shares[0].method
    setMethod(m)
    setIncludeMe(split.data.myShare > 0) // money I lent stays lent when edited
    setValues(Object.fromEntries(split.data.shares.map((s) => [s.personId, m === 'EQUAL' ? '' : String(s.input ?? s.amount)])))
  }, [split.data])

  const save = useMoneyMutation(
    () =>
      api<Split>(`/transactions/${txn.id}/split`, {
        method: 'PUT',
        body: {
          method,
          includeMe,
          participants: Object.entries(values).map(([id, v]) => ({ personId: Number(id), value: method === 'EQUAL' ? null : Number(v || 0) })),
        },
      }),
    () => setEditing(false),
  )

  const selected = Object.keys(values).map(Number)
  const toggle = (id: number) =>
    setValues((v) => {
      const n = { ...v }
      if (id in n) delete n[id]
      else n[id] = ''
      return n
    })

  const preview = (() => {
    const n = selected.length + (includeMe ? 1 : 0)
    if (method === 'EQUAL' && n > 0) return `${money(Math.floor((txn.amount / n) * 100) / 100)} each`
    if (method === 'PERCENT') return `${selected.reduce((s, id) => s + Number(values[id] || 0), 0)}% assigned`
    if (method === 'EXACT') return `${money(selected.reduce((s, id) => s + Number(values[id] || 0), 0))} of ${money(txn.amount)} assigned`
    return ''
  })()

  const has = (split.data?.shares.length ?? 0) > 0
  if (!split.data || (part === 'linked' ? !has : has)) return null
  if (!editing && !has)
    return (
      <MoreRow icon={Users} onClick={() => setEditing(true)}>
        Split with friends
      </MoreRow>
    )
  if (!editing) {
    const s = split.data
    return (
      <div className="rounded-box bg-surface-2 p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-body font-semibold">{s.shares.length > 0 && s.myShare === 0 ? 'Money you lent' : 'Split with others'}</p>
            {!s?.shares.length && <p className="ink-2 text-caption">Paid for a group? Track who owes you.</p>}
            {s.shares.length > 0 && s.myShare === 0 && <p className="ink-2 text-caption">Not counted as your spending.</p>}
          </div>
          <Button variant="primary" size="sm" onClick={() => setEditing(true)}>
            {s?.shares.length ? 'Edit' : 'Split'}
          </Button>
        </div>
        {s && s.shares.length > 0 && (
          <div className="mt-3 space-y-2 text-label">
            {s.shares.map((x) => (
              <div key={x.id} className="flex items-center justify-between">
                <span>{x.personName} owes</span>
                <span className="tabular font-semibold">{money(x.amount)}</span>
              </div>
            ))}
            <div className="ink-2 flex justify-between border-t border-line-strong pt-2">
              <span>My share</span>
              <span className="tabular">{money(s.myShare)}</span>
            </div>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="anim-pop space-y-3 rounded-box bg-surface-2 p-4">
      <Segmented<Method>
        value={method}
        onChange={setMethod}
        className="bg-surface"
        options={[
          { value: 'EQUAL', label: 'Equally' },
          { value: 'EXACT', label: 'Amounts' },
          { value: 'PERCENT', label: 'Percent' },
        ]}
      />
      {method === 'EQUAL' && <Toggle checked={includeMe} onChange={setIncludeMe} label="Include me in the split" />}
      {(people.data ?? []).length === 0 && <p className="ink-2 text-label">Add people under the Splits tab first.</p>}
      <div className="space-y-1">
        {(people.data ?? []).map((p) => (
          <div key={p.id} className="flex items-center gap-2">
            {/* No shrink and no weight change on tap (both made the row jump); only the circle fills and the tick pops in. */}
            <button
              type="button"
              aria-pressed={p.id in values}
              onClick={() => toggle(p.id)}
              className="row flex min-h-11 flex-1 select-none items-center gap-3 rounded-box px-2 py-2 text-left text-body [-webkit-touch-callout:none]"
            >
              <span className={cx('flex h-6 w-6 shrink-0 items-center justify-center rounded-full border-2 transition-colors duration-quick', p.id in values ? 'border-ink accent-fill' : 'border-line-strong')}>
                <AnimatePresence initial={false}>
                  {p.id in values && (
                    <motion.span key="tick" initial={{ scale: 0.4, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.4, opacity: 0 }} transition={QUICK} className="flex">
                      <Check size={13} weight="bold" />
                    </motion.span>
                  )}
                </AnimatePresence>
              </span>
              {p.name}
            </button>
            {method !== 'EQUAL' && p.id in values && (
              <Input
                className="w-28 bg-surface py-2 text-right"
                inputMode="decimal"
                placeholder={method === 'PERCENT' ? '%' : '₹'}
                value={values[p.id]}
                onChange={(e) => setValues((v) => ({ ...v, [p.id]: e.target.value }))}
              />
            )}
          </div>
        ))}
      </div>
      <p className="ink-2 px-1 text-caption font-medium">{preview}</p>
      <ErrorText error={save.error} />
      <div className="flex gap-2">
        <Button variant="secondary" className="flex-1" onClick={() => setEditing(false)}>
          Cancel
        </Button>
        <Button className="flex-1" onClick={() => save.mutate(undefined)} disabled={save.isPending}>
          Save split
        </Button>
      </div>
    </div>
  )
}
