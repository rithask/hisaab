import { Users } from '@phosphor-icons/react'
import { useState, type ReactNode } from 'react'
import { api, type Person, type Txn } from '../api'
import { fromLocalInput, money, toLocalInput } from '../format'
import { useCategories, useMoneyMutation, usePeople } from '../hooks'
import { categoryIcon } from '../icons'
import { DateTimePicker } from './DatePicker'
import { Avatar, Button, cx, ErrorText, Field, Input, Segmented } from './ui'

/**
 * "Someone else paid": a friend paid for something, part of which was mine. Only my share is recorded, as
 * spending with no account, and it comes off what they owe me. Nothing is learned from it.
 */

export type ShareMode = 'HALF' | 'ALL' | 'EXACT'

/** My share of what they paid. */
export function shareOf(total: number, mode: ShareMode, exact: string) {
  if (!(total > 0)) return 0
  if (mode === 'ALL') return total
  if (mode === 'HALF') return Math.round((total / 2) * 100) / 100
  return Number(exact) || 0
}

/** Where I stand with someone, in words: "owes you ₹300", "you owe ₹150", "all square". */
export function standing(outstanding: number, name?: string) {
  const v = Math.round(outstanding * 100) / 100
  if (v > 0) return `${name ? `${name} owes` : 'owes'} you ${money(v)}`
  if (v < 0) return `you owe ${name ? `${name} ` : ''}${money(-v)}`
  return 'all square'
}

/** "Your share" picker: half (the usual), all of it, or an exact amount. */
export function ShareFields({ total, mode, setMode, exact, setExact }: { total: number; mode: ShareMode; setMode: (m: ShareMode) => void; exact: string; setExact: (v: string) => void }) {
  const share = shareOf(total, mode, exact)
  return (
    <div>
      <Segmented<ShareMode>
        value={mode}
        onChange={setMode}
        options={[
          { value: 'HALF', label: 'Half' },
          { value: 'ALL', label: 'All of it' },
          { value: 'EXACT', label: 'Exact' },
        ]}
      />
      {mode === 'EXACT' ? (
        <Input className="mt-2.5" inputMode="decimal" placeholder="Your share, ₹" value={exact} onChange={(e) => setExact(e.target.value.replace(/[^\d.]/g, ''))} aria-label="Your share" />
      ) : (
        <p className="ink-2 mt-2 px-1 text-[13px]">
          Your share <b className="tabular text-[var(--ink)]">{money(share)}</b>
          {total > 0 && mode === 'HALF' ? ` of ${money(total)}` : ''}
        </p>
      )}
    </div>
  )
}

/** What saving will do to the balance with this person. */
export function BalanceChange({ person, change }: { person?: Person; change: number }) {
  if (!person) return null
  const after = person.outstanding - change
  return (
    <p className="ink-2 rounded-2xl bg-[var(--surface-2)] px-4 py-3 text-[13px] leading-relaxed">
      <b className="text-[var(--ink)]">{person.name}</b>: {standing(person.outstanding)} → <b className="text-[var(--ink)]">{standing(after)}</b>
    </p>
  )
}

/** Choose a person, as chips. */
export function PersonChips({ people, value, onChange }: { people: Person[]; value: number | ''; onChange: (id: number) => void }) {
  if (people.length === 0) return <p className="ink-2 px-1 text-sm">Add people under Splits first.</p>
  return (
    <div className="flex flex-wrap gap-2">
      {people.map((p) => (
        <button
          key={p.id}
          type="button"
          aria-pressed={p.id === value}
          onClick={() => onChange(p.id)}
          className={cx('pressable flex h-11 items-center gap-2 rounded-full pl-1.5 pr-4 text-[14px] font-medium transition-colors duration-150', p.id === value ? 'bg-[var(--ink)] text-[var(--bg)]' : 'bg-[var(--surface-2)]')}
        >
          <Avatar name={p.name} size={32} />
          {p.name}
        </button>
      ))}
    </div>
  )
}

/**
 * Record or change something a friend paid for. From a person's page the person is fixed; when editing, the
 * transaction's current values are filled in.
 */
export function PaidByForm({ person, txn, onDone }: { person?: Person; txn?: Txn; onDone: () => void }) {
  const people = usePeople()
  const cats = useCategories()
  const [personId, setPersonId] = useState<number | ''>(txn?.paidBy?.id ?? person?.id ?? '')
  const [total, setTotal] = useState(txn?.paidTotal != null ? String(txn.paidTotal) : '')
  const initialMode: ShareMode = !txn ? 'HALF' : txn.amount === txn.paidTotal ? 'ALL' : txn.amount === shareOf(txn.paidTotal ?? 0, 'HALF', '') ? 'HALF' : 'EXACT'
  const [mode, setMode] = useState<ShareMode>(initialMode)
  const [exact, setExact] = useState(txn && initialMode === 'EXACT' ? String(txn.amount) : '')
  const [categoryId, setCategoryId] = useState<number | ''>(txn?.category?.id ?? '')
  const [where, setWhere] = useState(txn?.counterparty ?? '')
  const [when, setWhen] = useState(toLocalInput(txn?.occurredAt))
  const [notes, setNotes] = useState(txn?.notes ?? '')

  const value = Number(total)
  const share = shareOf(value, mode, exact)
  const who = (people.data ?? []).find((p) => p.id === personId)
  const valid = personId !== '' && value > 0 && share > 0 && share <= value
  const body = { personId, total: value, share, categoryId: categoryId || null, where, occurredAt: fromLocalInput(when), notes }
  const save = useMoneyMutation(() => (txn ? api(`/transactions/${txn.id}/paid-by`, { method: 'PUT', body }) : api('/transactions/paid-by', { body })), onDone)
  const expense = (cats.data ?? []).filter((c) => c.kind === 'EXPENSE')

  return (
    <form
      className="space-y-5"
      onSubmit={(e) => {
        e.preventDefault()
        if (valid) save.mutate(undefined)
      }}
    >
      {!person && (
        <Group label="Who paid">
          <PersonChips people={people.data ?? []} value={personId} onChange={setPersonId} />
        </Group>
      )}
      <Group label="What they paid">
        <Input autoFocus={!txn} inputMode="decimal" placeholder="₹" value={total} onChange={(e) => setTotal(e.target.value.replace(/[^\d.]/g, ''))} aria-label="What they paid" className="text-lg" />
      </Group>
      <Group label="Your share">
        <ShareFields total={value} mode={mode} setMode={setMode} exact={exact} setExact={setExact} />
      </Group>
      <Group label="Category">
        <div className="flex flex-wrap gap-2">
          {expense.map((c) => {
            const Icon = categoryIcon(c)
            const on = c.id === categoryId
            return (
              <button key={c.id} type="button" aria-pressed={on} onClick={() => setCategoryId(on ? '' : c.id)} className={cx('pressable flex h-10 items-center gap-1.5 rounded-full px-3.5 text-[13.5px] font-medium transition-colors duration-150', on ? 'bg-[var(--ink)] text-[var(--bg)]' : 'bg-[var(--surface-2)]')}>
                <Icon size={16} weight={on ? 'fill' : 'regular'} style={on ? undefined : { color: c.color }} />
                {c.name}
              </button>
            )
          })}
        </div>
      </Group>
      <Field label="Where (optional)">
        <Input value={where} onChange={(e) => setWhere(e.target.value)} placeholder="e.g. HP Petrol Pump" />
      </Field>
      <Field label="When">
        <DateTimePicker value={when} onChange={setWhen} max={toLocalInput()} label="When" />
      </Field>
      <Field label="Note (optional)">
        <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      {valid && <BalanceChange person={who} change={share - (txn && txn.paidBy?.id === personId ? txn.amount : 0)} />}
      <ErrorText error={save.error} />
      <Button type="submit" className="w-full" disabled={!valid || save.isPending}>
        <Users size={17} /> {save.isPending ? 'Saving…' : valid ? `Save your share ${money(share)}` : 'Enter what they paid'}
      </Button>
    </form>
  )
}

function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="ink-2 mb-2 px-1 text-[13px] font-medium">{label}</p>
      {children}
    </div>
  )
}
